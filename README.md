# Whew

A zero-deps library that wraps Java's `CompletableFuture` class. Provides
functions and macros for passing futures through handlers, looping over them,
mapping and so on. Deals with nested futures (when a future returns a future and
so on).

[manifold]: https://github.com/clj-commons/manifold
[auspex]: https://github.com/mpenet/auspex

One might thing about this library as yet another clone of [Manifold][manifold]
or [Auspex][auspex]. But the API is a bit different and it handles some corner
cases.

<!-- toc -->

- [Installation](#installation)
- [Usage](#usage)
- [API](#api)
  * [Creating futures](#creating-futures)
  * [Chaining Futures](#chaining-futures)
  * [Dereferencing](#dereferencing)
  * [Folding](#folding)
  * [Zipping, Any-of, One-of](#zipping-any-of-one-of)
  * [The Let Macro](#the-let-macro)
  * [For & Map](#for--map)
  * [Loop & Recur](#loop--recur)
  * [Timeout & Cancelling](#timeout--cancelling)
- [Misc](#misc)

<!-- tocstop -->

## Installation

Requires Java version at least 8. Add a dependency:

~~~clojure
;; lein
[com.github.igrishaev/whew "0.1.0"]

;; deps
com.github.igrishaev/whew {:mvn/version "0.1.0"}
~~~

## Usage

Import the library:

~~~clojure
(ns org.some.project
  (:require
    [whew.core :as $]))
~~~

It provides functions and macros named after their Clojure counterparts,
e.g. `map`, `future`, `loop`, etc. Thus, never `:use` this library but
`:require` it using an alias. Here and below we will use `$`.

A quick demo. Let's prepare a function that makes some IO, for example fetches
JSON from network.

~~~clojure
(defn get-json [code]
  (-> (format "https://http.dog/%d.json" code)
      (http/get {:as :json
                 :throw-exceptions true})
      :body))
~~~

Here is how you run it in a future:

~~~clojure
(def -f ($/future
          (get-json 101)))
~~~

The `future` macro accepts arbitrary block of code and wraps it into a future
that gets executed in the background. Now deref it, and you'll get a result:

~~~clojure
@-f

{:image
 {:avif "https://http.dog/101.avif"
  :jpg "https://http.dog/101.jpg"
  :jxl "https://http.dog/101.jxl"
  :webp "https://http.dog/101.webp"}
 :status_code 101
 :title "Switching Protocols"
 :url "https://http.dog/101"}
~~~

By derefing a future, you freeze the current thread forcing it to wait until the
future is ready. But you can assign a post-processing handler to it which gets
run in the background as well once a future has done its work. Adding a handler
returns a new future. Below, we compose a bit of HTML markup from a fetched
data:

~~~clojure
(-> -f
    ($/then [data]
      (-> data :image :jpg))
    ($/then [url]
      [:a {:src url}
       "Click me!"])
    (deref))

[:a {:src "https://http.dog/101.jpg"} "Click me!"]
~~~

Each `then` handler accepts a future and a binding symbol. It is bound to a
result of a previous future. The block of code can return either a flat value or
a future as well:

~~~clojure
(-> ($/future
      (get-json 201))
    ($/then [data]
      ($/future
        (do-something-else (:url data))))
    ($/then [response]
      ...))
~~~

You can enqueue as many `then` handles as you want.

Should any of them throw an exception, a future becomes failed, and no more
further `then` handlers apply. If you `deref` such a failed future, you'll get
an exception. To recover from it, there is another `catch` handler:

~~~clojure
(-> ($/future
      (get-json 333)) ;; weird code
    ($/catch [e]
      {:error true
       :message (ex-message e)})
    (deref))

{:error true, :message "clj-http: status 404"}
~~~

The binding `e` symbol is bound to an exception value occurred before. What's
important, it will be an **unwrapped exception**! By default, the
`CompletableFuture` class wraps any runtime exception into various classes like
`ExecutionException` or `CompletionException`. If you branch your logic
depending on exception class or inheritance, you'll need to get an `ex-cause`
first. But the `catch` macro handles it for you: the `e` variable will be of the
right class.

Whew provides a number of various macros to express logic throughout futures:
mixing and chaining them, zipping, waiting multiple futures for completion and so
on. The section below describes these in detail.

## API

### Creating Futures

Both `future` and `future-async` macros take an arbitrary block of code and
return a future that carries a result of this block:

~~~clojure
($/future 42)

($/future-async
  (let [...]
    (do-long-job ...)))
~~~

A future might return a future which returns a future which returs a future and
so on:

~~~clojure
($/future
  ($/future
    ($/future
      ($/future 42))))
~~~

Hanlde the result in a naive way, you'll have to deref such a future four times:

~~~clojure
@@@@($/future
      ($/future
        ($/future
          ($/future 42))))
~~~

But Whew knows how to handle such cases:

~~~clojure
(-> ($/future
      ($/future
        ($/future
          ($/future 42))))
    ($/then [x] (inc x))
    (deref))
43
~~~

There is a special `deref` function that takes folding into account:

~~~clojure
(-> ($/future
      ($/future
        ($/future
          ($/future 42))))
    ($/deref))
42
~~~

Usually you don't need to deref futures neither with the standard `deref` nor a
custom `$/deref`.

The `future-sync` macro takes a block of code but executes it immediately in the
same thread and produces a **completed** future. When a future is completed, it
means it can be `deref`-fed right now without waiting. This is useful when you
want just to mimic a future.

The following piece of code will throw immediately:

~~~clojure
($/future-sync
  (let [a 1 b 2]
    (/ 0 0)))
~~~

The `->future` function turns any value into a completed future. Any `Throwable`
instance will produce a failed future: the one than cannot be propagated through
`then` handlers, but only `catch`.

~~~clojure
(-> ($/->future 1)
      ($/then [x]
        (inc x))
      ($/deref))

;; 2

(-> ($/->future (ex-info "boom" {:a 1}))
    ($/then [x]
      (inc x))
    ($/catch [e]
      {:data (ex-data e)})
    ($/deref))

;; {:data {:a 1}}
~~~

The `future?` predicate checks if a fiven value is a future:

~~~clojure
($/future? ($/future 1))
true

($/future? 1)
false
~~~

The `failed?` predicated checks if a future has failed. Pay attention that it
might take some time to detect it:

~~~clojure
(def -f ($/future
          (Thread/sleep 5000)
          (/ 0 0)))

($/failed? -f)
false

;; wait for 5 seconds

($/failed? -f)
true
~~~

### On Executors

By default, the `CompletableFuture` class relies on the
`ForkJoinPool/commonPool` executor although it's possible to override it. By
running benchmarks, I noticed that the standard
`clojure.core.Agent/soloExecutor` used for built-in futures and agents is more
robust. Thus, when you spawn futures using `($/future)` and `($/future-async)`
macros, the `soloExecutor` executor is used.

The `future-via` macro acts like `future-async` but accepts a custom `Executor`
instance to work with. Any further `then` and `catch` handlers will be served
within the same executor as well. Here is an example of using a custom
two-threaded executor:

~~~clojure
(with-open [executor
            (Executors/newFixedThreadPool 2)]
  (-> ($/future-via [executor]
        (let [a 1 b 2]
          (+ a b)))
      ($/then [x]
        ...)))
~~~

You can also use a virtual executor that relies on virtual threads available
since Java 21:

~~~clojure
(with-open [e (Executors/newVirtualThreadPerTaskExecutor)]
  ($/future-via [a]
    ...))
~~~

The default executor which Whew relies on is stored in the
`whew.core/EXECUTOR_DEFAULT` variable, and it's initial value is
`Agent/soloExecutor`. You can switch it globally to something else as follows:

~~~clojure
($/set-executor! some-executor)
~~~

At the moment, Whew provides the following `Executor` constants:

- `$/EXECUTOR_CLJ_SOLO`: a built-it `Agent/soloExecutor` Clojure executor;
- `$/EXECUTOR_CLJ_POOLED`: a built-it `Agent/pooledExecutor` Clojure executor;
- `$/EXECUTOR_FJ_COMMON`: a default ForkJoin common pool.

### Chaining Futures

`Then` macro provides a new future based on the previous one:

~~~clojure
@(-> ($/future 1) ($/then [x] (inc x)))
2
~~~

It works with non-future values as well. Internally, they get transformed into a
completed future:

~~~clojure
@(-> 1 ($/then [x] (inc x)))
2
~~~

A macro `then-fn` acts the same but accepts a function which gets applied to a
result of a previous future:

~~~clojure
@(-> 1 ($/then-fn inc))
2
~~~

You can pass additional arguments as well:

~~~clojure
@(-> 1 ($/then-fn + 100))
101
~~~

The `catch` macro handles an exception occurred beforehand. Pay attention that
the second `inc` form didn't work because it doesn't apply to a failed future:

~~~clojure
@(-> ($/future 1)
     ($/then-fn inc) ;; works
     ($/then-fn / 0) ;; fails
     ($/then-fn inc) ;; unreached
     ($/catch [e]    ;; recovered
       :this-is-fine))

:this-is-fine
~~~

As it was mentioned above, the macro unwraps exceptions. The `e` variable will
be an instance of `ArithmeticException` but not `ExecutionException`.

The `catch-fn` macro acts the same but accepts 1-argument function that handles
an exception:

~~~clojure
@(-> ($/future 1)
     ($/then [x]
       (throw (ex-info "boom" {:foo 1})))
     ($/catch-fn ex-data)
     ($/then [data]
        {:ex-data data}))

{:ex-data {:foo 1}}
~~~

It accepts additional arguments like `then-fn` does but usually they're not
needed.

The `handle` macro handles both a result and an exception at once:

~~~clojure
@(-> ($/future 1)
     ($/handle [r e]
       {:result r :exception e}))

{:result 1 :exception nil}
~~~

Usually you check if an exception is nil to decide the logic. The `handle-fn`
macro is similar but accepts a 2-arity function which handles both a result and
an exception:

~~~clojure
@(-> ($/future 1)
     ($/handle-fn
       (fn [r e]
         {:result r :exception e})))

{:result 1 :exception nil}
~~~

In both cases, exceptions are unwrapped.

### Dereferencing

The standard `@` operator and the `deref` function get a value from a future but
don't' take multiple levels into account:

~~~clojure
@($/future
   ($/future
     ($/future
       ($/future 1))))

#object[...CompletableFuture 0x287238e4 "...[Completed normally]"]
~~~

You've to go to the end:

~~~clojure
@@@@($/future
     ($/future
       ($/future
         ($/future 1))))
1
~~~

But the `deref` function from Whew does the same with no issues:

~~~clojure
($/deref
  ($/future
    ($/future
      ($/future
        ($/future 1)))))
1
~~~

To not hang forever, it accepts an amount of milliseconds (how long to wait) and
a default value to return on timeout:

~~~clojure
($/deref ($/future
           (Thread/sleep 1000)
           :done)
         100
         :too-long)
:too-long
~~~

### Folding

Folding a future means removing its unnecessarily levels, for example:

~~~clojure
;; this
($/future ($/future ($/future 1)))

;; becomes this
($/future 1)
~~~

When a future is folded, only one `deref` is required to obtain a value. The
`fold` function does it:

~~~clojure
@($/fold ($/future ($/future ($/future 1))))
;; 1
~~~

The `deref` function described above is nothing but a combo of `fold` and `.get`
invocations.

Usually you don't need to fold futures manually as `then`, `catch` and other
macros do it for you.

### Zipping, Any-of, One-of

The `zip` macro accepts a number of forms. Each form is turned into an async
future. The result is a future that gets completed when all the futures complete
(either successfully or with an exception). It will have a vector of plain
values:

~~~clojure
(-> ($/zip 1 ($/future ($/future 2)) 3)
    ($/then [vs]
      {:values vs})
    (deref))

{:values [1 2 3]}
~~~

Should any of futures fail, the entire future fail with the same exception:

~~~clojure
(-> ($/zip 1 ($/future ($/future (/ 0 0))) 3)
    ($/then [vs]
      {:values vs})
    ($/catch [e]
      {:error (ex-message e)})
    (deref))

{:error "Divide by zero"}
~~~

The `all-of` function acts the same: it accepts a collection of futures and
returns a future when all the items are completed:

~~~clojure
(-> ($/all-of [1 ($/future ($/future 2)) 3])
    ($/then [vs]
      {:values vs})
    (deref))

{:values [1 2 3]}
~~~

The difference is, `all-of` is a function but not a macro. It's useful when you
have a collection of futures produced by other functions.

The `any-of` function takes a collection of futures and waits for the first
completed one. The result is a future that carries a value from this future:

~~~clojure
@($/any-of [($/future
              (Thread/sleep 300)
              :A)
            ($/future
              (Thread/sleep 200)
              :B)
            ($/future
              (Thread/sleep 100)
              :C)])

;; C
~~~

The result is `:C` because the third future was the fastest one to complete.

### The Let Macro

The `let` macros mimics the one from the standard Clojure library, but:

- any value (in the right binding part) can return a future;
- bindings must not depend on each other;
- the body is executed when all the futures are completed;
- the bady has access to derefed values but not futures.

Here is a small demo. We use the `get-json` function that fetches a piece of
data by a numeric code.

~~~clojure
@($/let [resp-101 ($/future (get-json 101))
         resp-202 ($/future (get-json 202))
         resp-404 ($/future (get-json 404))]
   {101 resp-101
    202 resp-202
    404 resp-404})

{101
 {:image
  {:jpg "https://http.dog/101.jpg"}
  :title "Switching Protocols"
  :url "https://http.dog/101"}
 202
 {:image
  {:jpg "https://http.dog/202.jpg"}
  :title "Accepted"
  :url "https://http.dog/202"}
 404
 {:image
  {:jpg "https://http.dog/404.jpg"}
  :title "Not Found"
  :url "https://http.dog/404"}}
~~~

What's important, all three `get-json` invocations are done in parallel. When
every of them is ready, the body gets access to their values.

Should any binding fail, the entire `let` node fails as well:

~~~clojure
@(-> ($/let [resp-101 ($/future (get-json 101))
             resp-321 ($/future (get-json 321)) ;; wrong code
             resp-404 ($/future (get-json 404))]
       {101 resp-101
        321 resp-321
        404 resp-404})
     ($/catch [e]
       (-> e
           ex-data
           (select-keys [:status :reason-phrase]))))

{:status 404 :reason-phrase "Not Found"}
~~~

If you unsure about a certain binding, protect it with a `catch` macro:

~~~clojure
@(-> ($/let [resp-101 ($/future (get-json 101))
             resp-321 (-> ($/future (get-json 321))
                          ($/catch [e]
                            {:error true
                             :code 321}))
             resp-404 ($/future (get-json 404))]
       {101 resp-101
        321 resp-321
        404 resp-404}))

{101
 {:title "Switching Protocols"
  :url "https://http.dog/101"}
 321 {:error true, :code 321}
 404
 {:title "Not Found"
  :url "https://http.dog/404"}}
~~~

### For & Map

The `for` macro acts like the standard `for` but wraps each body expression into
a future. The result is a future holding all dereferenced values. Here is how we
collect data for given codes:

~~~clojure
@($/for [code [100 101 200 201 202 500]]
   (get-json code))

[{...} {...} {...} {...} {...} {...}]
~~~

Should any body expression fail, the entire future fails as well. Use the
`catch` macro to handle an exception.

The macro supports special `:let`, `:when` and other options like the standart
`for` does:

~~~clojure
@($/for [code [100 101 200 201 202 500]
         :when (>= code 500)]
   (get-json code))

[{:status_code 500}]
~~~

### Loop & Recur

Sometimes you need a future that returns a future that returs... and so on until
something happens. This is where the `loop` macro helps. It reminds the standard
`loop/recur` combo but has the following features:

- it returns a future that is executed in the background;
- use a special `$/recur` form but not the standard `recur` from Clojure.core;
- the body can produce a future;
- bindings can be futures as well.

The example below fetches JSON data one by one. Every time a future gets
completed, it performs the same block of code using bindings passes through the
`$/recur` form.

---

The `loop` macro is used rarely with futures because most of the time, other
facilities are enough. `Loop` is needed when you don't have the entire dataset
in your hand, and fetch it from somewhere. A good example is pagination: you
fetch data by chunks and accumulate them somehow until the result is
empty. Thus, you cannot runs multiple futures at once as you don't know for how
long to proceed. This kind of fetching can be expressed as follows:

~~~clojure
(def PAGE_SIZE 100)

(-> ($/loop [acc []
             off 0]
      (let [result (fetch-items :foobar {:offset off :size PAGE_SIZE})
            items (-> result :response :items)]
        (if (seq items)
          ($/recur (into acc items) (+ off PAGE_SIZE))
          acc)))
    ($/then [items]
      (process-items items))
    ($/catch [e]
      (log/errorf e "error: %s" 42)
      (report-error e)))
~~~

### Timeout & Cancelling

Any future can be limited in time with two strateges. First, it fails with a
timeout exception, and it's up to you how to handle this. Second, you specify a
default value for this future which comes into play on timeout.

The `timeout` macro has two bodies for both cases. The first form accepts a
future and a number of milliseconds. It assigns a timeout to a future. The
following example will fail because the sleep time is longer than the timeout:

~~~clojure
@(-> ($/future
       (Thread/sleep 1000))
     ($/timeout 100)
     ($/catch [e]
       {:error (type e)}))

{:error java.util.concurrent.TimeoutException}
~~~

Pay attention that the `TimeoutException` instance has no message: the
`(ex-message e)` form will return nil.

The macro acceps an arbitrary block of code as a default value for a future when
it breaches timeout:

~~~clojure
@(-> ($/future
       (Thread/sleep 1000))
     ($/timeout 100
        (let [a 1 b 2]
          (println "recovering from timeout")
          {:some ["other" :value]}))
     ($/catch [e]
       {:error (type e)})
     ($/then [x]
        (println "final handler")
        {:data x}))

;; recovering from timeout
;; final handler
;; {:data {:some ["other" :value]}}
~~~

Most likely you don't need to set timeouts explicitly: modern HTTP clients allow
to pass timeout in settings when making a call. The same applies to any
libraries working with sockets. But in rare cases, an explicit timeout helps.

Canceling is something different to timeout. It is when you ask to reject a
future spawned previously. Canceling a completed future has no effect. But if it
has not been completed or failed before, a cancellation request completes a
future with `CancellationException`. Later or, such a future can be checked for
cancellation state with a predicate.

The `cancel` function tries to cancel a future. The result is a boolean value
meaning if the attempt was successful or not. Canceling a non-future value has
no effect, and the result will be `nil`:

~~~clojure
(def -f ($/future 1))

($/cancel -f)
false ;; already completed
~~~

Let's try a slow future:

~~~clojure
(def -f ($/future
          (Thread/sleep 5000)
          (println "DONE")))

($/cancel -f)

($/cancelled? -f)
true

@-f ;; throws
;; Execution error (CancellationException)
~~~

Pay attention that you **will see** the `DONE` line printed! This is because of
implementation of the `CompletableFuture` class: canceling it doesn't interrupt
the current evaluation.

If you emit a cancellation request before a future has been started there won't
be any background cancellation. The following tests proves that:

~~~clojure
(let [p1 (promise)
      p2 (promise)]
  (with-open [e (Executors/newFixedThreadPool 1)]
    (let [f1 ($/future-via [e]
               (Thread/sleep 2000)
               (println "DONE 1")
               (deliver p1 true))
          _ (Thread/sleep 500)
          f2 ($/future-via [e]
               (Thread/sleep 2000)
               (println "DONE 2")
               (deliver p2 true))]
      (is ($/cancel f1))
      (is ($/cancel f2))))
  (is (realized? p1))
  (is (not (realized? p2))))
~~~

Above, we have an executor with one thread only. We spawn a future `f1` which
takes 2 seconds to complete, and wait for half of a second to let it start. The
second future `f2` will stay in a queue until `f1` is done. Then we cancel both
futures. The `f1` accepts a cancellation request in the middle of getting
processed. It gets canceled finally although its work was done: you'll see the
"DONE 1" printing and the `p1` promise will be delivered. But as `f2` has not
been started, it gets removed from a queue of the executor. You won't see "DONE
2", nor the `p2` promise will be delivered.

## Misc

~~~
©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©
Ivan Grishaev, 2025. © UNLICENSE ©
©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©
~~~
