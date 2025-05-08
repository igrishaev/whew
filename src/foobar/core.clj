(ns foobar.core
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
   (java.util.concurrent CompletableFuture
                         CompletionException
                         ExecutionException
                         Executors
                         TimeUnit
                         TimeoutException)
   (java.util.function Function
                       Supplier)))


(set! *warn-on-reflection* true)

(alias 'cc 'clojure.core)
(alias '$ 'foobar.core)


(defmacro supplier ^Supplier [& body]
  `(reify Supplier
     (get [this#]
       ~@body)))


(defmacro function ^Function [[this bind] & body]
  `(reify Function
     (apply [~this ~bind]
       ~@body)))


(defmacro future [& body]
  `(CompletableFuture/supplyAsync
    (supplier ~@body)))


(defmacro future-async [& body]
  `(CompletableFuture/supplyAsync
    (supplier ~@body)))


(defmacro future-via
  {:style/indent 1}
  [[executor] & body]
  `(CompletableFuture/supplyAsync
    (supplier ~@body)
    ~executor))


(defmacro future-sync [& body]
  `(CompletableFuture/completedFuture
    (do ~@body)))


(defn future? [x]
  (instance? CompletableFuture x))


(defn throwable? [x]
  (instance? Throwable x))


(defn ->failed ^CompletableFuture [^Throwable e]
  (CompletableFuture/failedFuture e))


(defn failed? ^Boolean [f]
  (when (future? f)
    (.isCompletedExceptionally ^CompletableFuture f)))


(defn ->future ^CompletableFuture [x]
  (cond
    ($/future? x) x
    ($/throwable? x) ($/->failed x)
    :else ($/future-sync x)))


(def ^Function -FUNC-FOLDER
  (function [this x]
    (if ($/future? x)
      (.thenCompose ^CompletableFuture x this)
      (future-sync x))))


(defn fold ^CompletableFuture [^CompletableFuture f]
  (.thenCompose f -FUNC-FOLDER))


(defmacro then
  {:style/indent 1}
  [f [bind] & body]
  `(cc/let [func#
            (function [func# ~bind]
              (if ($/future? ~bind)
                (.thenCompose ~(with-meta bind {:tag `CompletableFuture}) func#)
                ($/fold ($/future ~@body))))]
     (.thenCompose (->future ~f) func#)))


(defn then-fn
  {:style/indent 0}
  (^CompletableFuture [f func]
   (-> f ($/then [x]
           (func x))))
  (^CompletableFuture [f func & args]
   (-> f ($/then [x]
                 (apply func x args)))))


(defmacro chain
  {:style/indent 0}
  [f & funcs]
  `(fold
    (-> ($/->future ~f)
        ~@(cc/for [func funcs]
            `(then-fn ~func)))))


(defn e-unwrap ^Throwable [^Throwable e]
  (cond

    (instance? CompletionException e)
    (recur (ex-cause e))

    (instance? ExecutionException e)
    (recur (ex-cause e))

    :else e))

(defmacro catch
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
  ([^CompletableFuture f]
   (-> f fold .get))

  ([^CompletableFuture f timeout-ms timeout-val]
   (try
     (-> f fold (.get timeout-ms TimeUnit/MILLISECONDS))
     (catch TimeoutException e
            timeout-val))))


(def ^Class ^:const FUT_ARR_CLASS
  (Class/forName "[Ljava.util.concurrent.CompletableFuture;"))


(defn future-array? [x]
  (instance? FUT_ARR_CLASS x))


(defn future-array
  ^"[Ljava.util.concurrent.CompletableFuture;" [coll]
  (if (future-array? coll)
    coll
    (into-array CompletableFuture coll)))


(defmacro let [bindings & body]
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


(defmacro zip [& forms]
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


(defn zip-futures ^CompletableFuture [futures]
  (if (empty? futures)
    ($/->future [])
    (cc/let [fa
             (->> futures
                  (cc/map $/->future)
                  ($/future-array))]
      (-> (CompletableFuture/allOf fa)
          (then [_]
            (cc/mapv $/deref fa))))))


(defmacro for [bindings & body]
  (cc/let [FA (gensym "FA")]
    `(cc/let [~FA
              (future-array
               (cc/for [~@bindings]
                 (future ~@body)))]
       (-> (CompletableFuture/allOf ~FA)
           (then [_#]
             (cc/mapv deref ~FA))))))

(defn map
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


(defn cancel ^Boolean [f]
  (when ($/future? f)
    (.cancel ^CompletableFuture f true)))


(defn cancelled? ^Boolean [f]
  (when ($/future? f)
    (.isCancelled ^CompletableFuture f)))


(defmacro recur [& args]
  `(-> [~@args]
       (with-meta {::recur? true})))


(defn recur? [x]
  (some-> x meta ::recur?))


(defmacro throw!
  ([message]
   `(throw (new RuntimeException ~message)))
  ([message & args]
   `(throw! (format ~message ~@args))))


(defmacro loop [bindings & body]
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
                    (cc/let [~@(reduce
                                (fn [acc [i bind]]
                                  (-> acc
                                      (conj bind)
                                      (conj `(nth ~result ~i))))
                                []
                                (enumerate (cc/map first pairs)))]
                      (-> (future ~@body)
                          (.thenCompose func#))))

                  ($/future? ~result)
                  (.thenCompose ~(with-meta result {:tag `CompletableFuture}) func#)

                  :else
                  ($/future-sync ~result)))]

       (-> (future
             (cc/let [~@bindings]
               ~@body))
           (.thenCompose func#)))))


(defmacro timeout
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
