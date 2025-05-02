(ns foobar.core
  (:refer-clojure :exclude [future
                            future?
                            catch
                            loop
                            recur
                            let])
  (:import
   (java.util.function Function
                       Supplier)
   (java.util.concurrent CompletableFuture)
   (java.util.concurrent Executors)))


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

#_
(defmacro future [& body]
  `(CompletableFuture/completedFuture
    (do ~@body)))


#_
(defn fold
  ^CompletableFuture [^CompletableFuture f]
  (cc/let [^CompletableFuture fut (new CompletableFuture)]
    (.thenApplyAsync f
                     (function [this result]
                       (if (instance? CompletableFuture result)
                         (.thenApplyAsync ^CompletableFuture result this)
                         (.complete fut result))))
    fut))


#_
(fold (future 123))

(defn future? [x]
  (instance? CompletableFuture x))

(defn throwable? [x]
  (instance? Throwable x))

(defn ->future ^CompletableFuture [x]
  (cond
    (future? x)
    x

    (throwable? x)
    (CompletableFuture/failedFuture x)

    ;; deferred?

    :else
    (CompletableFuture/completedFuture x)))

(defmacro then
  {:style/indent 1}
  [f [bind] & body]
  `(cc/let [func#
            (function [func# ~bind]
              (if (future? ~bind)
                (.thenCompose ~(with-meta bind {:tag `CompletableFuture}) func#)
                (future ~@body)))]
     (.thenCompose (->future ~f) func#)))


(defn fold
  ^CompletableFuture [^CompletableFuture f]
  (-> f (then [x] x)))


;; then-fn
;; chain


;; TODO: fold
(defmacro catch
  {:style/indent 1}
  [f [e] & body]
  `(.exceptionally ~f (function [~e] ~@body)))


(defn enumerate [coll]
  (map-indexed vector coll))


;; TODO: fold
(defmacro let [bindings & body]
  (cc/let [FUTS (gensym "futs")
           pairs (partition 2 bindings)]
    `(cc/let [~FUTS
              (into-array
               CompletableFuture
               [~@(for [form (map second pairs)]
                    `(future ~form))])]
       (-> (CompletableFuture/allOf ~FUTS)
           (then [_#]
             (cc/let [~@(reduce
                         (fn [acc [i bind]]
                           (-> acc
                               (conj bind)
                               (conj `(deref (nth ~FUTS ~i)))))
                         []
                         (enumerate (map first pairs)))]
               ~@body))))))


(defmacro zip [& forms]
  `(list ~@(for [form forms]
             `(future ~form))))


;; pvalues
;; for


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
                              (enumerate (map first pairs)))]
                    (-> (future ~@body)
                        (.thenCompose func#)))

                  (future? ~result)
                  (.thenCompose ~(with-meta result {:tag `CompletableFuture}) func#)

                  :else
                  (CompletableFuture/completedFuture ~result)))]

       (-> (future
             (cc/let [~@bindings]
               ~@body))
           (.thenCompose func#)))))

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


(a/loop [i 0]
   (if (= i LIM)
     :done
     (a/recur (inc i))))




;; completeOnTimeout
;; orTimeout
