(ns jgood.warp-estimate.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[warp-estimate starting]=-"))
   :start      (fn []
                 (log/info "\n-=[warp-estimate started successfully]=-"))
   :stop       (fn []
                 (log/info "\n-=[warp-estimate has shut down successfully]=-"))
   :middleware (fn [handler _] handler)
   :opts       {:profile :prod}})
