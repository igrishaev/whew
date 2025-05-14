(ns whew.core
  "
  A number of wrappers on top of CompletableFuture.
  "
  (:refer-clojure :exclude [future
                            future?
                            catch
                            for
                            map
                            mapv
                            deref
                            pvalues
                            loop
                            recur
                            let])
  (:import
   (clojure.lang Agent)
   (java.util.concurrent CompletableFuture
                         CompletionException
                         ExecutionException
                         TimeUnit
                         ForkJoinPool
                         Executor
                         TimeoutException)
   (java.util.function Function
                       BiConsumer
                       BiFunction
                       Supplier)))


(set! *warn-on-reflection* true)

(alias 'cc 'clojure.core)
(alias '$ 'whew.core)


;;
;; Executors
;;

(def ^Executor EXECUTOR_CLJ_SOLO
  Agent/soloExecutor)

(def ^Executor EXECUTOR_CLJ_POOLED
  Agent/pooledExecutor)

(def ^Executor EXECUTOR_FJ_COMMON
  (ForkJoinPool/commonPool))

(def ^Executor EXECUTOR_DEFAULT
  EXECUTOR_CLJ_SOLO)


(defn set-executor!
  "
  Set a default global executor for all async futures.
  "
  [^Executor executor]
  (alter-var-root (var EXECUTOR_DEFAULT)
                  (fn [_]
                    executor)))


(defmacro biconsumer
  ^BiConsumer [[a b] & body]
  `(reify BiConsumer
     (accept [this# ~a ~b]
       ~@body)))


(defmacro bifunction
  ^BiFunction [[a b] & body]
  `(reify BiFunction
     (apply [this# ~a ~b]
       ~@body)))


(defmacro supplier
  "
  Produce a Supplier instance from a block of code.
  "
  ^Supplier [& body]
  `(reify Supplier
     (get [this#]
       ~@body)))


(defmacro function
  "
  Produce a Function instance from a block of code.
  The first binding argument represents the instance
  for recursive calls. The second argument is bound
  to the incoming parameter.
  "
  ^Function [[this bind] & body]
  `(reify Function
     (apply [~this ~bind]
       ~@body)))


(defmacro future-via
  "
  Spawn a new CompletableFuture within a custom executor
  instance (e.g. a fixed thread executor pool, virtual
  executor and so on).
  "
  {:style/indent 1}
  ^CompletableFuture [[^Executor executor] & body]
  `(CompletableFuture/supplyAsync
    (supplier ~@body)
    ~executor))


(defmacro future-async
  "
  Produce an asynchronous CompletableFuture
  instance from a block of code.
  "
  ^CompletableFuture [& body]
  `(future-via [EXECUTOR_DEFAULT]
     ~@body))


(defmacro future
  "
  Acts like `future-async`.
  "
  ^CompletableFuture [& body]
  `(future-async ~@body))


(defmacro future-sync
  "
  Evaluate a block of code and emit a completed
  future instance with the result. Should the block
  fail with an exception, return a failed future
  with that exeption. The block is executed synchronously
  in the current thread.
  "
  ^CompletableFuture [& body]
  `(try
     (cc/let [result# (do ~@body)]
       (CompletableFuture/completedFuture result#))
     (catch Throwable e#
       (CompletableFuture/failedFuture e#))))


(defn future?
  "
  Check if a given argument is an instance
  of CompletableFuture.
  "
  ^Boolean [x]
  (instance? CompletableFuture x))


(defn failed?
  "
  Check if a future instance has already failed.
  For non-future values, return nil.
  "
  ^Boolean [f]
  (when (future? f)
    (.isCompletedExceptionally ^CompletableFuture f)))


;; how to turn a value into a future
(defprotocol IFuture
  (-to-future [x]))


(extend-protocol IFuture

  nil
  (-to-future [_]
    (future-sync nil))

  Object
  (-to-future [this]
    (future-sync this))

  CompletableFuture
  (-to-future [this]
    this)

  Throwable
  (-to-future [this]
    (CompletableFuture/failedFuture this)))


(defn ->future
  "
  Turn a value into a future. Relies on the
  `IFuture` protocol.
  "
  ^CompletableFuture [x]
  (-to-future x))


(def ^Function -FUNC-FOLDER
  "
  A special Function instance to fold/collapse
  nested futures into a flat one.
  "
  (function [this x]
    (if ($/future? x)
      (.thenCompose ^CompletableFuture x this)
      (future-sync x))))


(defn fold
  "
  Turn a future that returns a future, that returns
  a future, and so on... into a one-level future.
  "
  ^CompletableFuture [^CompletableFuture f]
  (.thenCompose f -FUNC-FOLDER))


(defmacro handle
  "
  Handles both a result and a possible exception in place.
  - r is bound to a result, or nil;
  - e is bount to an exception, or nil;
  - body is an arbitrary block of code that checks if
    e is nil and produces either a value, a future or
    throws an exception.

  @(-> (future 42)
       (handle [r e]
         (if e
           {:error (ex-message e)}
           (future (inc r)))))
  43

  Returns a new future.
  "
  {:style/indent 1}
  ^CompletableFuture [f [r e] & body]
  `($/fold
    (.handle ($/fold (->future ~f))
             (bifunction [~r ~e]
               (cc/let [~e (some-> ~e e-unwrap)]
                 ~@body)))))


(defmacro handle-fn
  "
  Like `handle` but accepts 2-arity function with
  a result and an exception. Returns a new future.
  "
  {:style/indent 0}
  ^CompletableFuture [f func]
  `(handle ~f [r# e#]
           (~func r# e#)))


(defmacro then
  "
  Apply a block of code to a future. Better used
  with the -> macro as follows:

  (-> (future 42)
      (then [x]
        (let [a 1 b 2]
          (+ a b x))))

  The first argument is a future, which might be nested.
  The second binding artument is a value that comes
  from the future. The block of code produces a new
  value for the future. It might return another future
  as well.

  Return a new CompletableFuture instance.
  "
  {:style/indent 1}
  [f [bind] & body]
  `(cc/let [func#
            (function [func# ~bind]
              (if ($/future? ~bind)
                (.thenCompose ~(with-meta bind {:tag `CompletableFuture}) func#)
                ($/fold ($/future ~@body))))]
     (.thenCompose (->future ~f) func#)))


(defn then-fn
  "
  Like `then` but accepts a function that accepts
  a result of a future, and produces either a new
  value or a future.

  (-> (future 42)
      (then-fn inc))

  Also accepts additional arguments to the function:

  (-> (future 42)
      (then-fn + 1 2 3))

  Return a new CompletableFuture instance.
  "
  {:style/indent 0}
  (^CompletableFuture [f func]
   (-> f ($/then [x]
           (func x))))
  (^CompletableFuture [f func & args]
   (-> f ($/then [x]
           (apply func x args)))))


(defmacro chain
  "
  Pass a future though a series of 1-arity functions,
  each one accepting a value and returning either
  a new value or a future.
  "
  {:style/indent 0}
  [f & funcs]
  `(fold
    (-> ($/->future ~f)
        ~@(cc/for [func funcs]
            `(then-fn ~func)))))


(defn e-unwrap
  "
  Given a CompletionException or ExecutionException
  instance, dig recursively for a wrapped exception.
  "
  ^Throwable [^Throwable e]
  (cond

    (instance? CompletionException e)
    (recur (ex-cause e))

    (instance? ExecutionException e)
    (recur (ex-cause e))

    :else e))

(defmacro catch
  "
  Handles an exception that comes from a future.
  The `e` binding argument is assigned to an exception
  instance. The exception is unwrapped in advance
  meaning instead of ExecutionException you'll get
  its cause.

  Returns a new CompletableFuture instance with a value
  produced by a block of code.
  "
  {:style/indent 1}
  [f [e] & body]
  `(cc/let [func#
            (function [func# e#]
              (cc/let [~e (e-unwrap e#)]
                ~@body))]
     (-> ~f
         ($/->future)
         ($/fold)
         (.exceptionally func#)
         ($/fold))))


(defn catch-fn
  "
  Like `catch` but accepts a 1-arity function
  that handles an unwrapped exception.
  "
  {:style/indent 0}
  (^CompletableFuture [f func]
   (-> f ($/catch [e]
           (func e))))
  (^CompletableFuture [f func & args]
   (-> f ($/catch [e]
           (apply func e args)))))


(defn enumerate [coll]
  (cc/map-indexed vector coll))


(defn deref
  "
  Like the standard `deref` but flattens a nested
  future before .get-ting a value from it. Might
  be called with a timeout and a default value.
  "
  ([^CompletableFuture f]
   (-> f fold .get))

  ([^CompletableFuture f timeout-ms timeout-val]
   (try
     (-> f fold (.get timeout-ms TimeUnit/MILLISECONDS))
     (catch TimeoutException _
       timeout-val))))


(def ^Class ^:const FUT_ARR_CLASS
  (Class/forName "[Ljava.util.concurrent.CompletableFuture;"))


(defn future-array?
  "
  Is it a native array of CompletableFuture instances?
  "
  [x]
  (instance? FUT_ARR_CLASS x))


(defn future-array
  "
  Turn a Clojure collection of CompletableFuture
  instances into a native array.
  "
  ^"[Ljava.util.concurrent.CompletableFuture;" [coll]
  (if (future-array? coll)
    coll
    (into-array CompletableFuture coll)))


(defmacro let
  "
  Like `let` but:
  - each binding value might produce a future;
  - the body will be executed with dereferenced
    values when all the futures are ready.

  Binding should not depend on each other (overlap).
  The result is a new CompletableFuture instance
  with a valur produced by the block of code.

  @(let [a (future (future 1))
         b (future (future 2))]
    (+ a b))
  3
  "
  [bindings & body]
  (cc/let [FUTS (gensym "futs")
           pairs (partition 2 bindings)]
    `(cc/let [~FUTS
              (future-array
               [~@(cc/for [form (cc/map second pairs)]
                    `($/future ~form))])]
       (-> (CompletableFuture/allOf ~FUTS)
           (then [_#]
             (cc/let [~@(reduce
                         (fn [acc [i bind]]
                           (-> acc
                               (conj bind)
                               (conj `(-> ~FUTS
                                          (nth ~i)
                                          ($/deref)))))
                         []
                         (enumerate (cc/map first pairs)))]
               ~@body))))))


(defmacro zip
  "
  Turn a number of forms into futures. Return a new
  CompletableFuture instance with a vector of values
  in the same order.

  @(zip 1 (future 2) 3)
  [1 2 3]
  "
  [& forms]
  (if (empty? forms)
    `($/->future [])
    (cc/let [FUTS (gensym "FUTS")]
      `(cc/let [~FUTS
                (future-array
                 [~@(cc/for [form forms]
                      `(future ~form))])]
         (-> (CompletableFuture/allOf ~FUTS)
             (then [_#]
               (cc/mapv $/deref ~FUTS)))))))


(defn all-of
  "
  Accepts a collection of values or futures and
  returns a new future with a vector of values
  in the same order.
  "
  ^CompletableFuture [futures]
  (if (empty? futures)
    ($/->future [])
    (cc/let [fa
             (->> futures
                  (cc/map $/->future)
                  ($/future-array))]
      (-> (CompletableFuture/allOf fa)
          (then [_]
            (cc/mapv $/deref fa))))))


(defn any-of
  "
  Accepts a collection of values or futures. Returns
  a new future with a value from the first completed
  (or failed) future.
  "
  ^CompletableFuture [futures]
  (if (empty? futures)
    (->future nil)
    (cc/let [fa
             (->> futures
                  (cc/map $/->future)
                  ($/future-array))]
      (-> (CompletableFuture/anyOf fa)
          (fold)))))


(defmacro for
  "
  Like `for` but:
  - each body expression is wrapped into a future;
  - the result is a future with a vector of values
    in the same order.

  All the default `for` features are supported (:let,
  :when and so on).

  @(for [x [1 2 3]]
    {:x x})
  [{:x 1} {:x 2} {:x 3}]
  "
  [bindings & body]
  (cc/let [FA (gensym "FA")]
    `(cc/let [~FA
              (future-array
               (cc/for [~@bindings]
                 (future ~@body)))]
       (-> (CompletableFuture/allOf ~FA)
           (then [_#]
             (cc/mapv deref ~FA))))))

(defn map
  "
  Like `map` but works with collection(s) of futures.
  Any value of a collection might be a future. The `f`
  function gets applied with `then-fn`. It might produce
  a future as well.

  Can accept multiple collections; arity of the  `f` function
  should match the number of collections. Every nth future
  is taken from collections, and one all of them are ready,
  plain values are passed into a function.

  Return a lazy collection of futures.
  "
  ([f coll]
   (cc/map (fn [v]
             (then-fn v f))
           coll))

  ([f coll & colls]
   (apply cc/map
          (fn [& vs]
            (let [fa
                  (->> vs
                       (cc/map ->future)
                       (future-array))]
              (-> (CompletableFuture/allOf fa)
                  (then [_]
                    (apply f (cc/mapv $/deref fa))))))
          coll
          colls)))


(defn cancel
  "
  Try to cancel a given future.
  "
  ^Boolean [f]
  (when ($/future? f)
    (.cancel ^CompletableFuture f true)))


(defn cancelled?
  "
  Check if a future has been canceled.
  "
  ^Boolean [f]
  (when ($/future? f)
    (.isCancelled ^CompletableFuture f)))


(defmacro recur
  "
  A special value for loop (see below).
  "
  [& args]
  `(-> [~@args]
       (with-meta {::recur? true})))


(defn recur? [x]
  (some-> x meta ::recur?))


(defmacro throw!
  ([message]
   `(throw (new RuntimeException ~message)))
  ([message & args]
   `(throw! (format ~message ~@args))))


(defmacro loop
  "
  Like `loop` but produces a future that produces
  a fututure that produces... and so on. Key features:

  - return a future with a handler that calls itself
    recursively;
  - binding values can be futures as well;
  - for recur, there is a special macro with the same
    naem (don't mix it with the standard recur).

  @(loop [i 0
            acc []]
     (if (< i 3)
       (recur (inc i)
                (future
                  (future
                    (conj acc i))))
       (future
         (future
           acc))))
  [1 2 3]
  "
  [bindings & body]
  (cc/let [result (gensym "result")
           pairs (partition 2 bindings)
           amount (count pairs)]
    `(cc/let [func#
              (function [func# ~result]
                (cond

                  ($/recur? ~result)
                  (cc/let [amount# (count ~result)]
                    (when-not (= ~amount amount#)
                      ($/throw! "wrong number or arguments to recur: expected %s but got %s"
                                ~amount amount#))

                    (-> ($/let [~@(reduce
                                   (fn [acc [i bind]]
                                     (-> acc
                                         (conj bind)
                                         (conj `(nth ~result ~i))))
                                   []
                                   (enumerate (cc/map first pairs)))]
                          ~@body)
                        (.thenCompose func#)))

                  ($/future? ~result)
                  (.thenCompose ~(with-meta result {:tag `CompletableFuture}) func#)

                  :else
                  ($/future-sync ~result)))]

       (-> (future
             (cc/let [~@bindings]
               ~@body))
           (.thenCompose func#)))))


(defmacro timeout
  "
  A macro to set timeout for given future. Without
  a block of code, just limits the execution time.
  When time is up, a future ends up with a timeout
  exception.

  If the block of code presents, it provides a value
  for a future should it completes due to timeout.

  Examples:

  ;; let this future spend up to 1 second but no more
  (-> (future (some-long-action ...))
      (timeout 1000))

  ;; the same but provide a fallback
  (-> (future (some-long-action ...))
      (timeout 1000
         (log/error ....)
         {:error 'try next time'}))
  "
  {:style/indent 1}
  ([f timeout-ms]
   `(.orTimeout ~f ~timeout-ms TimeUnit/MILLISECONDS))
  ([f timeout-ms & body]
   `(cc/let [result# (do ~@body)]
      (fold
       (.completeOnTimeout ($/->future ~f)
                           result#
                           ~timeout-ms TimeUnit/MILLISECONDS)))))




;; ---------



#_
(def LIM 1000000)

#_
@(f/loop [i 0]
   (if (= i LIM)
     :done
     (f/recur (inc i))))

#_
(require '[manifold.deferred :as d])


#_
@(d/loop [i 0]
   (if (= i LIM)
     :done
     (d/recur (inc i))))


#_
(require '[qbits.auspex :as a])


#_
@(a/loop [i 0]
   (if (= i LIM)
     :done
     (a/recur (inc i))))


#_
(a/loop [i 0]
  (if (= i LIM)
    :done
    (a/recur (inc i))))
