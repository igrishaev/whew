#_
(defmacro timeout
  {:style/indent 1}
  [f [timeout-ms] & body]
  `(cc/let [result# (do ~@body)]
     (fold
      (.completeOnTimeout (this/->future ~f)
                          result#
                          ~timeout-ms TimeUnit/MILLISECONDS))))


#_
(defn timeout [^CompletableFuture f timeout-ms]
  (.orTimeout f timeout-ms TimeUnit/MILLISECONDS))


(time
 (do
   (mapv deref (doall
                (clojure.core/for [code [101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500]]
                  ($/future-async
                    (get-json code)))))
   nil))


(time
 (do
   (mapv deref (doall
                (clojure.core/for [code [101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500 101 202 500]]
                  ($/future-via [clojure.lang.Agent/soloExecutor]
                    (get-json code)))))
   nil))
