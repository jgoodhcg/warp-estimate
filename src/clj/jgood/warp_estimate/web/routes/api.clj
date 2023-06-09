(ns jgood.warp-estimate.web.routes.api
  (:require
   [cheshire.core :as json]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as string]
   [hiccup.core :refer [html]]
   [hiccup.page :refer [html5]]
   [integrant.core :as ig]
   [jgood.warp-estimate.web.controllers.health :as health]
   [jgood.warp-estimate.web.middleware.exception :as exception]
   [com.rpl.specter :as sp]
   [jgood.warp-estimate.web.middleware.formats :as formats]
   [reitit.coercion.malli :as malli]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [reitit.swagger :as swagger]
   [ring.adapter.undertow.websocket :as ws]
   [ring.util.http-response :as http-response]
   [ring.util.response :as response])
  (:import
   [java.util UUID]))

(def route-data
  {:coercion   malli/coercion
   :muuntaja   formats/instance
   :swagger    {:id ::api}
   :middleware [;; query-params & form-params
                parameters/parameters-middleware
                  ;; content-negotiation
                muuntaja/format-negotiate-middleware
                  ;; encoding response body
                muuntaja/format-response-middleware
                  ;; exception handling
                coercion/coerce-exceptions-middleware
                  ;; decoding request body
                muuntaja/format-request-middleware
                  ;; coercing response bodys
                coercion/coerce-response-middleware
                  ;; coercing request parameters
                coercion/coerce-request-middleware
                  ;; exception handling
                exception/wrap-exception]})

(defn htmx-page [body]
  (-> (html5
       {:lang "en"}
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:script {:src "https://cdn.tailwindcss.com"}]
        [:script {:src "https://unpkg.com/htmx.org@1.8.6"}]
        [:script {:src "https://unpkg.com/htmx.org/dist/ext/ws.js"}]]

       [:body.bg-gradient-to-b.from-indigo-800.to-indigo-950.min-h-screen.px-4.pt-4
        body])
      http-response/ok
      (http-response/content-type "text/html")))

(defn htmx-component
  [content]
  (-> content html http-response/ok (http-response/content-type "text/html")))

(def rooms (atom {}))

(defn main-container [& content]
  [:div.flex.justify-center
   [:div.bg-slate-200.rounded.p-4.flex.flex-col.items-center.w-fit.align-center.gap-2
    {:id "main-container"}
    content]])

(defn active-btn [opts label]
  [:button.bg-blue-500.hover:bg-blue-700.text-white.font-semibold.py-2.px-4.rounded.w-full.md:w-64
   opts
   label])

(defn passive-btn [opts label]
  [:button.bg-transparent.text-blue-500.border.border-blue-500.hover:bg-blue-500.hover:text-white.font-semibold.py-2.px-4.rounded.w-full.md:w-64
   opts
   label])

(defn landing []
  (main-container
   (active-btn
    {:hx-post     "/api/create-room-form"
     :hx-target   "#main-container"
     :hx-swap     "outerHTML"
     :hx-push-url "/api/create-room-form"}
    "Create Room")
   (passive-btn
    {:hx-post     "/api/join-room-form"
     :hx-target   "#main-container"
     :hx-swap     "outerHTML"
     :hx-push-url "/api/join-room-form"}
    "Join a Room")))

(defn create-room!
  "create room if it doesn't exist"
  [room-id]
  (swap! rooms
         (fn [rooms]
           (if (not (-> rooms (contains? room-id)))
             (-> rooms (assoc room-id {:channels #{}}))
             rooms))))

(defn team-area [room]
  [:div.flex.flex-col.p-4.md:w-64
   {:id "team-area"}
   (-> room
       :people
       (->> (sort-by :name))
       (->> (map (fn [{:keys [name point]}]
                   [:div.flex.flex-row.gap-2
                    [:span name]
                    [:span point]]))))])

(defn pointing-area []
  (let [fib-seq [1 2 3 5 8 13 21 50 100]]
    [:div.grid.grid-cols-3.gap-4
     (for [num fib-seq]
       [:button.bg-blue-500.text-white.font-bold.py-2.px-4.rounded.hover:bg-blue-700
        {:type       "button"
         :hx-trigger "click"
         :hx-swap    "none"
         :hx-vals    (str "{\"point\":" num "}")
         :ws-send    true}
        (str num)])
     [:button.bg-blue-500.text-white.font-bold.py-4.px-4.rounded.hover:bg-blue-700.col-span-3
      {:type       "button"
       :hx-trigger "click"
       :hx-swap    "none"
       :hx-vals    "{\"point\": \"?\"}"
       :ws-send    true}
      "?"]]))

(defn joined-room [room-id name room]
  (main-container
   [:div
    {:id          "main-container"
     :hx-ext      "ws"
     :ws-connect  (str "/api/connect-room/" room-id "/" name)}
    [:div.flex.flex-col.md:flex-row
     (team-area room)
     (pointing-area)]]))

(defn page-or-comp [content {:keys [request-method]}]
  (if (= request-method :get)
    (htmx-page content)
    (htmx-component content)))

(def adjectives
  ["artisanal" "bespoke" "craft" "farm-to-table" "free-range" "heirloom"
   "locavore" "organic" "post-ironic" "quinoa" "raw" "sustainable" "vegan"
   "wildcrafted" "slow" "crispy" "neat" "tofu" "kombucha" "recycled"])

(def nouns
  ["beard" "bike" "cardigan" "coffee" "cold-press" "curated" "distillery"
   "kale" "kimchi" "leggings" "man-bun" "matcha" "mustache" "plaid" "pourover"
   "scenester" "single-origin" "succulent" "ukulele" "vintage" "vinyl" "yoga"])

(defn random-name []
  (let [adjective (rand-nth adjectives)
        noun (rand-nth nouns)]
    (string/capitalize (str adjective " " noun))))

(defn basic-input [opts]
  [:input.bg-white.border.border-gray-300.rounded.text-gray-700.px-3.py-2.leading-tight.focus:outline-none.focus:border-blue-500.w-full.md:w-64
   opts])

(defn create-room-form []
  (let [room-id  (str (java.util.UUID/randomUUID))
        form-url (str "/api/room/" room-id)]
    (main-container
     [:form.flex.flex-col.space-y-4
      {:hx-post     form-url
       :hx-trigger  "submit"
       :hx-push-url form-url}
      [:label.block.text-gray-700.font-semibold "Your Name"]
      (basic-input
       {:type        "text"
        :placeholder "Enter Your Name"
        :id          "name"
        :name        "name"})
      (active-btn
       {:type "submit"}
       "Create Room")])))

(defn join-room-form []
  (main-container
     [:form.flex.flex-col.space-y-4
      {:action "/api/submit-join-form" :method "post"}
      [:label.block.text-gray-700.font-semibold "Your Name"]
      (basic-input
       {:type        "text"
        :placeholder "Enter Your Name"
        :id          "name"
        :name        "name"})
      [:label.block.text-gray-700.font-semibold "Your Name"]
      (basic-input
       {:type        "text"
        :placeholder "Enter Room ID"
        :id          "room-id"
        :name        "room-id"})
      (active-btn
       {:type "submit"}
       "Join Room")]))

;; Routes
(defn api-routes [_opts]
  [["/swagger.json"
    {:get {:no-doc  true
           :swagger {:info {:title "jgood.warp-estimate API"}}
           :handler (swagger/create-swagger-handler)}}]
   ["/health"
    {:get health/healthcheck!}]

   ["/"
    (fn [r] (-> (landing) (page-or-comp r)))]

   ["/create-room-form"
    (fn [r] (-> (create-room-form) (page-or-comp r)))]

   ["/join-room-form"
    (fn [r] (-> (join-room-form) (page-or-comp r)))]

   ["/submit-join-form"
    (fn [{:keys [params]}]
      (let [room-id (:room-id params)
            name    (:name params)]
        (-> (response/redirect (str "/api/room/" room-id "?name=" name)))))]

   (->> [nil "" "1"] (some #(when (not (string/blank? %)) %)))
   ["/room/{room-id}"
    (fn [{:keys [path-params query-params params]
         :as   r}]
      (let [room-id (:room-id path-params)
            qp-name (:name query-params)
            p-name  (:name params)
            name    (->> [p-name qp-name (random-name)]
                         (some #(when (not (string/blank? %)) %)))]
        (create-room! room-id)
        (-> (joined-room room-id name (-> @rooms (get room-id)))
            (page-or-comp r))))]

   ["/connect-room/{room-id}/{name}"
    (fn [{:keys [path-params]}]
      (let [room-id (:room-id path-params)
            name    (:name path-params)]
        {:undertow/websocket
         {:on-open          (fn [{:keys [channel]}]
                              (println (str name " has joined " room-id))
                              (swap! rooms
                                     (fn [rooms]
                                       (update-in
                                        rooms
                                        [room-id :people] conj {:channel channel
                                                                :name    name})))
                              (let [room (-> @rooms (get room-id))]
                                (doseq [{:keys [channel]} (-> room :people)]
                                  (ws/send (-> (team-area room) html) channel))))
          :on-message       (fn [{:keys [channel data]}]
                              (let [data  (json/parse-string data true)
                                    point (:point data)]
                                (pprint {:location :on-message
                                         :data     data
                                         :point    point})
                                (swap! rooms
                                       (fn [rooms]
                                         (->> rooms
                                              (sp/transform [(sp/keypath room-id :people)
                                                             sp/ALL
                                                             #(= (:channel %) channel)]
                                                            (fn [person]
                                                              (merge person {:point point}))))))
                                (let [room (-> @rooms (get room-id))]
                                  (doseq [{:keys [channel]} (-> room :people)]
                                    (ws/send (-> (team-area room) html) channel)))))
          :on-close-message (fn [{:keys [channel message]}]
                              (println (str name " has left " room-id))
                              (swap! rooms (fn [rooms]
                                             (update-in
                                              rooms
                                              [room-id :people]
                                              (fn [people]
                                                (remove #(= (:channel %) channel) people)))))
                              (let [room (-> @rooms (get room-id))]
                                (doseq [{:keys [channel]} (-> room :people)]
                                  (ws/send (-> (team-area room) html) channel))))}}))]
   ])

(derive :reitit.routes/api :reitit/routes)

(defmethod ig/init-key :reitit.routes/api
  [_ {:keys [base-path]
      :or   {base-path ""}
      :as   opts}]
  [base-path route-data (api-routes opts)])

(comment
  (-> @rooms keys)
  (-> @rooms
      (get "8bdef2ab-ab5a-4382-9326-ef4a932a0c10")
      :people
      (->> (map :name))
      #_(->> (map (fn [{ch :channel}]
                  (ws/send (-> [:div {:id "pointing-area"} "hello there"] html) ch))))
      )
  )
