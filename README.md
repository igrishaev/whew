# Whew

A zero-deps library that wraps Java's `CompletableFuture` class. Provides
functions and macros for passing futures through handlers, looping over them,
mapping and so on. Deals with nested futures (when a future returns a future and
so on).

[manifold]: https://github.com/clj-commons/manifold
[auspex]: https://github.com/mpenet/auspex

One might thing about this library as yet another clone of [Manifold][manifold]
or [Auspex][auspex]. But the API is a bit different and handles some corner
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

It provides plenty of functions and macros named after their Clojure
counterparts, e.g. `map`, `future`, `loop`, etc. Thus, never `:use` this library
but `:require` it using an alias. Here and below we will use `$`. A quick demo:

~~~clojure
(defn get-json [code]
  (-> (format "https://http.dog/%d.json" code)
      (http/get {:as :json})
      :body))

(def -f ($/future
          (get-json 101)))
~~~

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

## In Detail



~~~
©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©
Ivan Grishaev, 2025. © UNLICENSE ©
©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©©
~~~
