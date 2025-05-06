(ns foobar.core-test
  (:import
   (java.util.concurrent ExecutionException))
  (:require
   [clojure.test :refer :all]
   [foobar.core :as core]))

(deftest test-future-ok

  (let [f (core/future
            (let [a 1 b 2]
              (+ a b)))]

    (is (= 3 @f))
    (is (core/future? f))
    (is (not (core/future? 42)))

    (is (core/future? (core/->future 1)))
    (is (core/future? (core/->future
                       (core/future 1))))

    (is (= 1
           (-> 1
               (core/->future)
               (core/->future)
               (core/->future)
               (core/->future)
               (deref)))))

  (let [f (core/->future (ex-info "boom" {}))]
    (is (core/future? f))
    (is (core/failed? f))
    (try
      @f
      (is false)
      (catch Exception e
        (is (instance? ExecutionException e))
        (is (= "clojure.lang.ExceptionInfo: boom {}"
               (ex-message e))))))

  (let [f (core/->failed (ex-info "boom" {}))]
    (try
      @f
      (is false)
      (catch Exception e
        (is (instance? ExecutionException e))
        (is (= "clojure.lang.ExceptionInfo: boom {}"
               (ex-message e))))))

  (let [f (core/future
            (let [a 1 b 2]
              (/ (+ a b) 0)))]
    (is (core/future? f))
    (is (core/failed? f)))

  (try
    (core/future-sync
      (let [a 1 b 2]
        (/ (+ a b) 0)))
    (is false)
    (catch ArithmeticException e
      (is (= "Divide by zero"
             (ex-message e))))))


(deftest test-chaining

  (let [f
        (-> (core/future 1)
            (core/then [x]
              (inc x))
            (core/then [x]
              (core/future (inc x)))
            (core/then [x]
              (core/future (core/future (inc x)))))]
    (is (= 4 @f)))

  (let [f
        (-> (core/future 1)
            (core/then [x]
              (core/future (/ x 0)))
            (core/then [x]
              (core/future 100500))
            (core/catch [e]
              (core/future
                (core/future
                  {:type (str (class e))
                   :message (ex-message e)})))
            (core/then [m]
              (core/future
                (assoc m :foo 42))))]

    (is (= {:type "class java.lang.ArithmeticException"
            :message "Divide by zero"
            :foo 42}
           @f)))

  ;; then-fn
  ;; chain

  )
