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
                         Executors
                         TimeUnit
                         TimeoutException)
   (java.util.function Function
                       Supplier)))


(set! *warn-on-reflection* true)

(alias 'cc 'clojure.core)
(alias 'f 'foobar.core)


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


(defn ->future ^CompletableFuture [x]
  (cond
    (future? x) x
    (throwable? x) (->failed x)
    :else (future-sync x)))


(def ^Function -FUNC-FOLDER
  (function [this x]
    (if (future? x)
      (.thenComposeAsync ^CompletableFuture x this)
      (future-sync x))))

(defn fold ^CompletableFuture [^CompletableFuture f]
  (.thenComposeAsync f -FUNC-FOLDER))

(defmacro then
  {:style/indent 1}
  [f [bind] & body]
  `(cc/let [func#
            (function [func# ~bind]
              (if (future? ~bind)
                (.thenComposeAsync ~(with-meta bind {:tag `CompletableFuture}) func#)
                (future ~@body)))]
     (.thenComposeAsync (->future ~f) func#)))


(defn then-fn
  (^CompletableFuture [f func]
   (-> f (then [x]
           (func x))))
  (^CompletableFuture [f func & args]
   (-> f (then [x]
           (apply func x args)))))


;; then-fns
;; chain
;; ->
;; ->>

;; TODO: fold
(defmacro catch
  {:style/indent 1}
  [f [e] & body]
  `(.exceptionally ~f (function [~e] ~@body)))


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


(defn future-array
  ^"[Ljava.util.concurrent.CompletableFuture;" [coll]
  (into-array CompletableFuture coll))


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
                                          (deref)))))
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
             (cc/mapv deref ~FUTS))))))


#_
(defn zip-futures ^CompletableFuture [futures]
  (-> futures
      (future-array)
      (all-of)
      (then [_#]
        (cc/mapv deref ~FUTS))))


(defmacro pvalues [& forms]
  `(zip ~@forms))


(defmacro for [bindings & body]
  (cc/let [FUTS (gensym "FUTS")]
    `(cc/let [~FUTS
              (future-array
               (cc/for [~@bindings]
                 (future ~@body)))]
       (-> (CompletableFuture/allOf ~FUTS)
           (then [_#]
             (cc/mapv deref ~FUTS))))))

#_
(f/for [x [1 2 3]]
  (get-user x))


(defn all-of ^CompletableFuture [coll]
  (-> coll
      future-array
      (CompletableFuture/allOf)))


(defn map
  ([f coll]
   (cc/map (fn [v]
             (then-fn v f))
           coll))

  ([f coll & colls]
   (apply cc/map
          (fn [& vs]
            (let [futs
                  (->> vs
                       (cc/map ->future)
                       (cc/map fold)
                       (future-array))]
              (-> (all-of futs)
                  (then [_]
                    (apply f (cc/map deref futs))))))
          coll
          colls)))


(defn cancel ^Boolean [^CompletableFuture f]
  (.cancel f true))


;; macro

(defn recur [& args]
  (-> []
      (into args)
      (with-meta {::recur? true})))


#_
(defmacro recur [& args]
  `(CompletableFuture/completedFuture (with-meta [~@args] {::recur? true})))

;; completeOnTimeout
;; orTimeout


;; macro
(defn recur? [x]
  (some-> x meta ::recur?))


(defmacro loop [bindings & body]
  (cc/let [result (gensym "result")
           pairs (partition 2 bindings)]
    `(cc/let [func#
              (function [func# ~result]
                (cond

                  (recur? ~result)
                  (cc/let [~@(reduce
                              (fn [acc [i bind]]
                                (-> acc
                                    (conj bind)
                                    (conj `(nth ~result ~i))))
                              []
                              (enumerate (cc/map first pairs)))]
                    (-> (future ~@body)
                        (.thenComposeAsync func#)))

                  (future? ~result)
                  (.thenComposeAsync ~(with-meta result {:tag `CompletableFuture}) func#)

                  :else
                  (future-sync ~result)))]

       (-> (future
             (cc/let [~@bindings]
               ~@body))
           (.thenComposeAsync func#)))))


;; -----------


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
