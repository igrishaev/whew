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
                         Executors
                         TimeUnit
                         TimeoutException)
   (java.util.function Function
                       Supplier)))


(set! *warn-on-reflection* true)

(alias 'cc 'clojure.core)
(alias 'this 'foobar.core)


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
    (this/future? x) x
    (this/throwable? x) (this/->failed x)
    :else (this/future-sync x)))


(def ^Function -FUNC-FOLDER
  (function [this x]
    (if (this/future? x)
      (.thenComposeAsync ^CompletableFuture x this)
      (future-sync x))))


(defn fold ^CompletableFuture [^CompletableFuture f]
  (.thenComposeAsync f -FUNC-FOLDER))


(defmacro then
  {:style/indent 1}
  [f [bind] & body]
  `(cc/let [func#
            (function [func# ~bind]
              (if (this/future? ~bind)
                (.thenComposeAsync ~(with-meta bind {:tag `CompletableFuture}) func#)
                (this/fold (this/future ~@body))))]
     (.thenComposeAsync (->future ~f) func#)))


(defn then-fn
  (^CompletableFuture [f func]
   (-> f (this/then [x]
           (func x))))
  (^CompletableFuture [f func & args]
   (-> f (this/then [x]
           (apply func x args)))))


(defmacro chain
  {:style/indent 0}
  [f & funcs]
  `(fold
    (-> (this/->future ~f)
        ~@(cc/for [func funcs]
            `(then-fn ~func)))))


(defmacro catch
  {:style/indent 1}
  [f [e] & body]
  `(cc/let [func#
            (function [func# e#]
              (cc/let [~e
                       (if (instance? CompletionException e#)
                         (ex-cause e#)
                         e#)]
                ~@body))]
     (.exceptionally (->future ~f) func#)))


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
                    `(future ~form))])]
       (-> (CompletableFuture/allOf ~FUTS)
           (then [_#]
             (cc/let [~@(reduce
                         (fn [acc [i bind]]
                           (-> acc
                               (conj bind)
                               (conj `(-> ~FUTS
                                          (nth ~i)
                                          (this/deref)))))
                         []
                         (enumerate (cc/map first pairs)))]
               ~@body))))))


(defmacro zip [& forms]
  (cc/let [FUTS (gensym "FUTS")]
    `(cc/let [~FUTS
              (future-array
               [~@(cc/for [form forms]
                    `(future ~form))])]
       (-> (CompletableFuture/allOf ~FUTS)
           (then [_#]
             (cc/mapv this/deref ~FUTS))))))


(defn zip-futures ^CompletableFuture [futures]
  (cc/let [fa
           (->> futures
                (cc/map this/->future)
                (this/future-array))]
    (-> (CompletableFuture/allOf fa)
        (then [_]
          (cc/mapv this/deref fa)))))


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
                    (apply f (cc/mapv this/deref fa))))))
          coll
          colls)))


(defn cancel ^Boolean [f]
  (when (this/future? f)
    (.cancel ^CompletableFuture f true)))


(defn cancelled? ^Boolean [f]
  (when (this/future? f)
    (.isCancelled ^CompletableFuture f)))


(defmacro recur [& args]
  `(-> [~@args]
       (with-meta {::recur? true})))


(defn recur? [x]
  (some-> x meta ::recur?))


(defmacro loop [bindings & body]
  (cc/let [result (gensym "result")
           pairs (partition 2 bindings)]
    `(cc/let [func#
              (function [func# ~result]
                (cond

                  (this/recur? ~result)
                  (cc/let [~@(reduce
                              (fn [acc [i bind]]
                                (-> acc
                                    (conj bind)
                                    (conj `(nth ~result ~i))))
                              []
                              (enumerate (cc/map first pairs)))]
                    (-> (future ~@body)
                        (.thenComposeAsync func#)))

                  (this/future? ~result)
                  (.thenComposeAsync ~(with-meta result {:tag `CompletableFuture}) func#)

                  :else
                  (this/future-sync ~result)))]

       (-> (future
             (cc/let [~@bindings]
               ~@body))
           (.thenComposeAsync func#)))))


(defmacro timeout
  {:style/indent 1}
  ([f timeout-ms]
   `(.orTimeout ~f ~timeout-ms TimeUnit/MILLISECONDS))
  ([f timeout-ms & body]
   `(cc/let [result# (do ~@body)]
      (fold
       (.completeOnTimeout (this/->future ~f)
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
