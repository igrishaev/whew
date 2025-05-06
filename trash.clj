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
