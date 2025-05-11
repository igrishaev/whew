(ns demo
  (:require
   [whew.core :as $]
   [clj-http.client :as http]))

(defn get-json [code]
  ;; (Thread/sleep 1000)
  (-> (format "https://http.dog/%d.json" code)
      (http/get {:as :json
                 :throw-exceptions true})
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


(-> ($/future
      (get-json 201))
    ($/then [data]
      ($/future
        (do-something-else (:url data))))
    ($/then [response]
      ...))


(-> ($/future
      (get-json 333))
    ($/catch [e]
      {:error true
       :message (ex-message e)})
    (deref))

{:error true, :message "clj-http: status 404"}



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


($/future 42)

($/future-async
  (let [...]
    (do-long-job ...)))

@@@@($/future
      ($/future
        ($/future
          ($/future 42))))


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


@(-> ($/let [resp-101 ($/future (get-json 101))
             resp-321 ($/future (get-json 321))
             resp-404 ($/future (get-json 404))]
       {101 resp-101
        321 resp-321
        404 resp-404})
     ($/catch [e]
       (-> e
           ex-data
           (select-keys [:status :reason-phrase]))))


@(-> ($/let [resp-101 ($/future (get-json 101))
             resp-321 (-> ($/future (get-json 321))
                          ($/catch [e]
                            {:error true
                             :code 321}))
             resp-404 ($/future (get-json 404))]
       {101 resp-101
        321 resp-321
        404 resp-404}))


@($/for [code [100 101 200 201 202 500]]
   (get-json code))


@($/for [code [100 101 200 201 202 500]
         :when (>= code 500)]
   (get-json code))
