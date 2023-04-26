(ns jgood.warp-estimate.env
  (:require
    [clojure.tools.logging :as log]
    [jgood.warp-estimate.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[warp-estimate starting using the development or test profile]=-"))
   :start      (fn []
                 (log/info "\n-=[warp-estimate started successfully using the development or test profile]=-"))
   :stop       (fn []
                 (log/info "\n-=[warp-estimate has shut down successfully]=-"))
   :middleware wrap-dev
   :opts       {:profile       :dev
                :persist-data? true}})
