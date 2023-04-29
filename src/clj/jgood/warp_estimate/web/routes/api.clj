(ns jgood.warp-estimate.web.routes.api
  (:require
   [hiccup.core :refer [html]]
   [hiccup.page :refer [html5]]
   [integrant.core :as ig]
   [jgood.warp-estimate.web.controllers.health :as health]
   [jgood.warp-estimate.web.middleware.exception :as exception]
   [jgood.warp-estimate.web.middleware.formats :as formats]
   [reitit.coercion.malli :as malli]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [reitit.swagger :as swagger]
   [ring.adapter.undertow.websocket :as ws]
   [ring.util.http-response :as http-response]
   [clojure.pprint :refer [pprint]]
   [cheshire.core :as json])
  (:import [java.util UUID]))

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
   [:div.bg-slate-200.rounded.p-4.flex.flex-col.items-center.w-full.md:w-96.align-center.gap-2
    {:id "main-container"}
    content]])

(defn landing []
  (let [room-url (str "/api/room/" (java.util.UUID/randomUUID))]
    (main-container
      [:button.bg-blue-500.hover:bg-blue-700.text-white.font-semibold.py-2.px-4.rounded.w-full.md:w-64
       {:hx-post     room-url
        :hx-target   "#main-container"
        :hx-swap     "outerHTML"
        :hx-push-url room-url}
       "Create Room"]
      [:button.bg-transparent.text-blue-500.border.border-blue-500.hover:bg-blue-500.hover:text-white.font-semibold.py-2.px-4.rounded.w-full.md:w-64
       {:hx-post     "/api/join-room-form"
        :hx-target   "#main-container"
        :hx-swap     "outerHTML"
        :hx-push-url "/api/join-room-form"}
       "Join Room"])))

(defn join-form []
  (main-container
   [:form.flex.flex-col.space-y-4
    [:label.block.text-gray-700.font-semibold "Room ID"]
    [:input.bg-white.border.border-gray-300.rounded.text-gray-700.px-3.py-2.leading-tight.focus:outline-none.focus:border-blue-500.w-full.md:w-64 {:type "text" :placeholder "Enter Room ID" :required true}]
    [:button.bg-blue-500.hover:bg-blue-700.text-white.font-semibold.py-2.px-4.rounded.w-full.md:w-64
     "Join"]]))

(defn create-room!
  "create room if it doesn't exist"
  [room-id]
  (swap! rooms
         (fn [rooms]
           (if (not (-> rooms (contains? room-id)))
             (-> rooms (assoc room-id {:channels #{}}))
             rooms))))

(defn joined-room [room-id]
  (main-container
   [:div.bg-slate-200.rounded.p-4.grid.grid-cols-1.gap-y-4
    {:id         "main-container"
     :hx-ext     "ws"
     :ws-connect (str "/api/connect-room/" room-id)}
    (str "You are in room " room-id)
    [:div.outline.outline-indigo-500 {:id "msgs"}]]))

(defn page-or-comp [content {:keys [request-method]}]
  (if (= request-method :get)
    (htmx-page content)
    (htmx-component content)))

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

   ["/join-room-form"
    (fn [r] (-> (join-form) (page-or-comp r)))]

   ["/room/{room-id}"
    (fn [{:keys [path-params]
         :as r}]
      (let [room-id (:room-id path-params)]
        (create-room! room-id)
        (-> (joined-room room-id) (page-or-comp r))))]

   ["/connect-room/{room-id}"
    (fn [{:keys [path-params]}]
      (let [room-id (:room-id path-params)]
        {:undertow/websocket
         {:on-open          (fn [{:keys [channel]}]
                              (swap! rooms
                                     (fn [rooms]
                                       (update-in
                                        rooms
                                        [room-id :channels] conj channel)))
                              (println "WS open!"))
          :on-message       (fn [{:keys [channel data]}]
                              (let [data (json/parse-string data true)]
                                (pprint {:location :on-message
                                         :data     data})
                                (ws/send
                                 (-> [:div {:id "echo"} (:msg data)]
                                     html)
                                 channel)))
          :on-close-message (fn [{:keys [channel message]}] (println "WS closeed!"))}}))]
   ])

(derive :reitit.routes/api :reitit/routes)

(defmethod ig/init-key :reitit.routes/api
  [_ {:keys [base-path]
      :or   {base-path ""}
      :as   opts}]
  [base-path route-data (api-routes opts)])
