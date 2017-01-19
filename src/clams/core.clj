(ns clams.core
  (:require #_[cognician.config :as config]
            #_ [cognician.log :as log]
            #_ [cognician.semaphore :as semaphore]
            #_ [cognician.server.user :as user]
            [snake.core :as snake])
  (:import fi.solita.clamav.ClamAVClient
           [java.io BufferedWriter File FileInputStream FileWriter]
           java.text.SimpleDateFormat))
