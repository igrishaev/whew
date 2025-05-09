(ns whew.core-test
  (:import
   (java.util.concurrent ExecutionException
                         Executors))
  (:require
   [clojure.test :refer [deftest is]]
   [whew.core :as $]))

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

  (let [f ($/->future (ex-info "boom" {}))]
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


(deftest test-deref
  (let [f ($/future ($/future ($/future 42)))]
    (is (future? (deref f)))
    (is (int? ($/deref f))))

  (let [f ($/future ($/future ($/future (Thread/sleep 1000))))]
    (is (= ::too-long
           ($/deref f 50 ::too-long)))))


(deftest test-catch
  (let [f
        (-> ($/future
              ($/future
                (/ 0 0)))
            ($/catch [e]
              ($/future
                ($/future
                  {:type (type e)}))))]
    (is (= {:type java.lang.ArithmeticException}
           @f)))

  (let [f
        (-> ($/future
              ($/future
                (/ 0 0)))
            ($/catch-fn
              (fn [e a b]
                {:e (ex-message e)
                 :a a
                 :b b})
              :foo
              :bar))]
    (is (= {:e "Divide by zero" :a :foo :b :bar}
           @f))))


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

    (is (= {:message "Divide by zero"}
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
               (deref))))))


(deftest test-all-of
  (let [f ($/all-of [1
                     ($/future
                       ($/future
                         ($/future
                           2)))
                     3])]
    (is ($/future? f))
    (is (= [1 2 3] @f)))

  (let [f ($/all-of [])]
    (is (= [] @f)))

  (let [f ($/all-of [1
                     ($/future
                       ($/future
                         ($/future
                           (/ 0 0))))
                     3])]
    (is ($/future? f))
    (is (= {:type java.lang.ArithmeticException
            :message "Divide by zero"}
           (-> f
               ($/catch [e]
                 {:type (type e)
                  :message (ex-message e)})
               (deref))))))


(deftest test-any-of
  (let [f ($/any-of [($/future
                       (Thread/sleep 300)
                       :A)
                     ($/future
                       (Thread/sleep 200)
                       :B)
                     ($/future
                       (Thread/sleep 100)
                       :C)])]
    (is ($/future? f))
    (is (= :C @f)))

  (let [f ($/any-of [($/future
                       (Thread/sleep 300)
                       :A)
                     :B])]
    (is (= :B @f)))

  (let [f ($/any-of [($/future
                       (Thread/sleep 300)
                       :A)
                     ($/future
                       (Thread/sleep 200)
                       :B)
                     ($/future
                       (Thread/sleep 100)
                       (/ 0 0)
                       :C)])]
    (is ($/future? f))
    (is (= "Divide by zero"
           (-> f
               ($/catch-fn ex-message)
               (deref))))))


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


(deftest test-map
  (let [futs1
        (for [x (range 0 3)]
          ($/future
            ($/future
              ($/future x))))

        futs2
        ($/map (fn [x]
                 ($/future
                   ($/future
                     ($/future
                       (inc x)))))
               futs1)]

    (is (= [1 2 3]
           (mapv deref futs2))))

  (let [futs1
        (for [x (reverse (range 0 3))]
          ($/future
            ($/future
              ($/future
                (/ 1 x)))))

        futs2
        ($/map (fn [x]
                 ($/future
                   ($/future
                     ($/future
                       (+ x 100)))))
               futs1)]

    (is (= [201/2
            101
            {:type java.util.concurrent.ExecutionException
             :error "java.lang.ArithmeticException: Divide by zero"}]
           (mapv (fn [f]
                   (try
                     @f
                     (catch Throwable e
                       {:type (type e)
                        :error (ex-message e)})))
                 futs2)))))


(deftest test-cancel
  (let [f ($/->future 42)]
    (is (not ($/cancelled? f))))
  (is (nil? ($/cancelled? 42))))


(deftest test-loop
  (let [f
        ($/loop [i 0
                 acc []]
          (if (< i 3)
            ($/recur (inc i) (conj acc i))
            acc))]
    (is ($/future? f))
    (is (= [0 1 2] @f)))

  (let [f
        ($/loop [i 0
                 acc []]
          (if (< i 3)
            ($/recur (inc i)
                     ($/future
                       ($/future
                         (conj acc i))))
            ($/future
              ($/future
                acc))))]
    (is ($/future? f))
    (is (= [0 1 2] @f)))

  (let [f
        ($/loop [i 0
                 acc []]
          (if (< i 3)
            ($/recur (inc i) (conj acc i) :dunno)
            acc))]
    (is ($/future? f))
    (is (= "wrong number or arguments to recur: expected 2 but got 3"
           (-> f
               ($/catch-fn ex-message)
               (deref)))))

  (let [f
        ($/loop [i 0
                 acc []]
          (if (< i 3)
            ($/future
              ($/future
                ($/recur (inc i) (conj acc i))))
            acc))]
    (is ($/future? f))
    (is (= [0 1 2] @f)))

  (let [f
        ($/loop [i 0
                 acc []]
          (if (< i 3)
            ($/recur (inc i) (conj acc i))
            ($/future
              ($/future
                ($/future
                  acc)))))]
    (is ($/future? f))
    (is (= [0 1 2] @f)))

  (let [f
        ($/loop [i 3]
          (-> ($/future (/ i i))
              ($/then [_]
                ($/recur (dec i)))))]
    (is ($/future? f))

    (is (= {:type java.lang.ArithmeticException
            :message "Divide by zero"}
           (-> f
               ($/catch [e]
                 {:type (type e)
                  :message (ex-message e)})
               (deref))))))


(deftest test-timeout

  (let [f
        (-> ($/future
              (Thread/sleep 1000))
            ($/timeout 100))]

    (try
      @f
      (is false)
      (catch Throwable e
        (is (= "java.util.concurrent.TimeoutException"
               (ex-message e)))))

    (is (= {:type java.util.concurrent.TimeoutException}
           (-> f
               ($/catch [e]
                 {:type (type e)})
               (deref))))

    (let [f
          (-> ($/future
                (Thread/sleep 1000))
              ($/timeout 100
                (let [a 1 b 2]
                  ($/future
                    ($/future
                      (+ a b))))))]
      (is (= 3 @f)))))


(deftest test-future-via
  (with-open [executor
              (Executors/newFixedThreadPool 2)]
    (let [f ($/future-via [executor]
              (let [a 1 b 2]
                (+ a b)))]
      (is (= 3 @f)))))


(deftest test-handle
  (let [f
        (-> ($/future 42)
            ($/handle [r e]
              (if e
                ($/future
                  ($/future
                    {:type (class e)}))
                ($/future
                  ($/future
                    (inc r))))))]

    (is (= 43 @f)))

  (let [f
        (-> ($/future
              ($/future
                ($/future 42)))
            ($/handle [r e]
              (inc r)))]
    (is (= 43 @f)))

  (let [f
        (-> ($/future
              ($/future
                (/ 0 0)))
            ($/handle [r e]
              {:type (class e)}))]
    (is (= {:type java.lang.ArithmeticException}
           @f)))

  (let [f
        (-> ($/future
              ($/future 1))
            ($/handle [r e]
              (/ 0 0)))]
    (is (= {:type java.lang.ArithmeticException}
           (-> f
               ($/catch [e]
                 {:type (class e)})
               (deref)))))

  (let [f
        (-> ($/future
              ($/future
                (/ 0 0)))
            ($/handle [r e]
              e))]
    (is (= java.lang.ArithmeticException
           (type @f))))

  (let [f
        (-> ($/future 42)
            ($/handle-fn
              (fn [r e]
                (if e
                  ($/future
                    ($/future
                      {:type (class e)}))
                  ($/future
                    ($/future
                      (inc r)))))))]
    (is (= 43 @f)))

  (let [f
        (-> ($/future
              (/ 0 0))
            ($/handle-fn
              (fn [r e]
                (if e
                  ($/future
                    ($/future
                      {:type (class e)}))
                  ($/future
                    ($/future
                      (inc r)))))))]
    (is (= {:type java.lang.ArithmeticException}
           @f))))
