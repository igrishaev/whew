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

### Creating futures

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

The `future-via` macro acts like `future-async` but accepts a custom Executor
instance to work with. Any further `then` and `catch` handlers will be served
within the same executor as well.

~~~clojure
(with-open [executor
            (Executors/newFixedThreadPool 2)]
  (-> ($/future-via [executor]
        (let [a 1 b 2]
          (+ a b)))
      ($/then [x]
        ...)))
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

### Chaining Futures

then, then-fn, catch, catch-fn, handle, handle-fn

### Derefing Futures

deref, timeout

### Folding

### Zipping, Any-of, One-of

zip, any-of, one-of

### The Let Macro

### For & Map

### Loop & Recur

### Cancelling & Timeout

## Misc

~~~
©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©
Ivan Grishaev, 2025. © UNLICENSE ©
©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©
~~~
