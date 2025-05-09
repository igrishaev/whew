(ns demo
  (:require
   [whew.core :as $]
   [clj-http.client :as http]))

(defn get-json [code]
  (-> (format "https://http.dog/%d.json" code)
      (http/get {:as :json})
      :body))

(def -f ($/future
          (get-json 101)))

@-f

{:image
 {:avif "https://http.dog/101.avif",
  :jpg "https://http.dog/101.jpg",
  :jxl "https://http.dog/101.jxl",
  :webp "https://http.dog/101.webp"},
 :status_code 101,
 :title "Switching Protocols",
 :url "https://http.dog/101"}

(-> -f
    ($/then [data]
      (-> data :image :jpg))
    ($/then [url]
      [:a {:src url}
       "Click me!"])
    (deref))

[:a {:src "https://http.dog/101.jpg"} "Click me!"]


(-> ($/zip
      (get-json 200)
      (get-json 201)
      (get-json 202))
    ($/then [responses]
      (for [r responses]
        (-> r :image :jpg)))
    (deref))

("https://http.dog/200.jpg"
 "https://http.dog/201.jpg"
 "https://http.dog/202.jpg")
