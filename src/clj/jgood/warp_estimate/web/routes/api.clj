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
  [body]
  (-> body html http-response/ok (http-response/content-type "text/html")))

(def rooms (atom {}))

;; Routes
(defn api-routes [_opts]
  [["/swagger.json"
    {:get {:no-doc  true
           :swagger {:info {:title "jgood.warp-estimate API"}}
           :handler (swagger/create-swagger-handler)}}]
   ["/health"
    {:get health/healthcheck!}]

   ["/"
    (fn [_] (-> [:div.bg-slate-200.rounded.p-4.grid.grid-cols-1.gap-y-4
                {:id "main-container"}
                [:button.bg-purple-500.outline.outline-indigo-500.rounded.text-white
                 {:hx-post   (str "/api/create-room/" (java.util.UUID/randomUUID))
                  :hx-target "#main-container"
                  :hx-swap   "outerHTML"}
                 "Create Room"]
                [:button.outline.outline-indigo-500.rounded
                 "Join Room"]
                ]
               htmx-page))]

   ["/create-room/{room-id}"
    (fn [{:keys [path-params]}]
      (let [room-id (:room-id path-params)]
        (swap! rooms assoc room-id {:channels #{}})
        (-> [:div.bg-slate-200.rounded.p-4.grid.grid-cols-1.gap-y-4
             {:id         "main-container"
              :hx-ext     "ws"
              :ws-connect (str "/api/join-room/" room-id)}
             (str "You are in room " room-id)
             [:div.outline.outline-indigo-500 {:id "msgs"}]]
            htmx-component)))]

   ["/join-room/{room-id}"
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
