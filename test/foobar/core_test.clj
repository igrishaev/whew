(ns foobar.core-test
  (:import
   (java.util.concurrent ExecutionException))
  (:require
   [clojure.test :refer :all]
   [foobar.core :as $]))

(deftest test-future-ok

  (let [f ($/future
            (let [a 1 b 2]
              (+ a b)))]

    (is (= 3 @f))
    (is ($/future? f))
    (is (not ($/future? 42)))

    (is ($/future? ($/->future 1)))
    (is ($/future? ($/->future
                    ($/future 1))))

    (is (= 1
           (-> 1
               ($/->future)
               ($/->future)
               ($/->future)
               ($/->future)
               (deref)))))

  (let [f ($/->future (ex-info "boom" {}))]
    (is ($/future? f))
    (is ($/failed? f))
    (try
      @f
      (is false)
      (catch Exception e
        (is (instance? ExecutionException e))
        (is (= "clojure.lang.ExceptionInfo: boom {}"
               (ex-message e))))))

  (let [f ($/->failed (ex-info "boom" {}))]
    (try
      @f
      (is false)
      (catch Exception e
        (is (instance? ExecutionException e))
        (is (= "clojure.lang.ExceptionInfo: boom {}"
               (ex-message e))))))

  (let [f ($/future
            (let [a 1 b 2]
              (/ (+ a b) 0)))]
    (is ($/future? f))
    (is ($/failed? f)))

  (try
    ($/future-sync
     (let [a 1 b 2]
       (/ (+ a b) 0)))
    (is false)
    (catch ArithmeticException e
      (is (= "Divide by zero"
             (ex-message e))))))


(deftest test-chaining

  (let [f
        (-> ($/future 1)
            ($/then [x]
                    (inc x))
            ($/then [x]
                    ($/future (inc x)))
            ($/then [x]
                    ($/future ($/future (inc x)))))]
    (is (= 4 @f)))

  (let [f
        (-> ($/future 1)
            ($/then [x]
                    ($/future (/ x 0)))
            ($/then [x]
                    ($/future 100500))
            ($/catch [e]
                ($/future
                  ($/future
                    {:type (str (class e))
                     :message (ex-message e)})))
            ($/then [m]
                    ($/future
                      (assoc m :foo 42))))]

    (is (= {:type "class java.lang.ArithmeticException"
            :message "Divide by zero"
            :foo 42}
           @f)))

  (let [f
        (-> ($/future 1)
            ($/then-fn inc)
            ($/then-fn + 2)
            ($/then-fn
             (fn [x]
               ($/future
                 (* 10 x))))
            ($/then-fn
             (fn [x]
               ($/future
                 ($/future
                   (+ x 3))))))]
    (is (= 43 @f)))

  (let [f
        (-> ($/future 1)
            ($/chain
             inc
             (fn [x]
               ($/future
                 (* 10 x)))
             (fn [x]
               ($/future
                 ($/future
                   (+ x 3))))))]
    (is (= 23 @f))))


(deftest test-let
  (let [f
        ($/let [a ($/future 1)
                b ($/future
                    ($/future
                      ($/future 2)))
                c 3]
          (+ a b c))]
    (is ($/future? f))
    (is (= 6 @f)))

  (let [f
        ($/let [a ($/future 1)
                b ($/future
                    ($/future
                      ($/future
                        (/ 0 0))))
                c 3]
          (+ a b c))]

    (try
      @f
      (is false)
      (catch Exception e
        (is e)))

    (is ($/failed? f))

    (is (= {:message "java.lang.ArithmeticException: Divide by zero"}
           (-> f
               ($/catch [e]
                   {:message (ex-message e)})
               (deref)))))

  (let [f
        ($/let [a ($/future 1)]
          ($/let [b ($/future 2)]
            ($/let [c ($/future 3)]
              (+ a b c))))]
    (is (= 6 @f))))


(deftest test-zip
  (let [f ($/zip 1 ($/future
                     ($/future
                       ($/future 2))) 3)]
    (is ($/future? f))
    (is (= [1 2 3] @f)))

  (let [f ($/zip)]
    (is ($/future? f))
    (is (= [] @f)))

  (let [f ($/zip 1 ($/future
                     ($/future
                       ($/future
                         (/ 0 0)))) 3)]
    (is ($/future? f))
    (is (= {:type java.lang.ArithmeticException
            :message "Divide by zero"}
           (-> f
               ($/catch [e]
                 {:type (type e)
                  :message (ex-message e)})
               (deref)))))

  (let [f ($/zip-futures 1
                         ($/future
                           ($/future
                             ($/future
                               (/ 0 0))))
                         3)]
    (is ($/future? f))
    (is (= {:type java.lang.ArithmeticException
            :message "Divide by zero"}
           (-> f
               ($/catch [e]
                 {:type (type e)
                  :message (ex-message e)})
               (deref)))))

  (let [f ($/zip-futures)]
    (is ($/future? f))
    (is (= [] @f))))


(deftest test-for

  (let [f
        ($/for [a (range 0 3)
                b (range 6 9)
                :when
                (and (not= a 2) (not= b 7))]
          ($/future
            ($/future
              {:a a :b b})))]

    (is (= [{:a 0 :b 6} {:a 0 :b 8} {:a 1 :b 6} {:a 1 :b 8}]
           @f)))

  (let [f
        ($/for [a (reverse (range 0 3))]
          ($/future
            ($/future
              (/ a a))))]

    (is (= {:type java.lang.ArithmeticException
            :message "Divide by zero"}
           (-> f
               ($/catch [e]
                 {:type (type e)
                  :message (ex-message e)})
               (deref))))))


;; map

;; cancell
;; cancelled?

;; loop

;; timeout
