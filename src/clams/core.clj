(ns clams.core
  (:require #_[cognician.config :as config]
            #_ [cognician.log :as log]
            #_ [cognician.semaphore :as semaphore]
            #_ [cognician.server.user :as user]
            [snake.core :as snake])
  (:import fi.solita.clamav.ClamAVClient
           [java.io BufferedWriter File FileInputStream FileWriter]
           java.text.SimpleDateFormat))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Time helpers

(defn pretty-date
  ([date] (pretty-date date "yyyy/MM/dd HH:mm:ss"))
  ([date format]
   (let [df (SimpleDateFormat. format)]
     (.format df date))))

(defn now []
  (java.util.Date.))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Client and helpers

(defonce *av-client (atom nil))

(defn client
  ([] (client config/CLAM-AV-HOST config/CLAM-AV-PORT))
  ([host port]
   (if @*av-client
     @*av-client
     (reset! *av-client (ClamAVClient. host port)))))

(defn scan-reply [reply]
  (let [r (String. reply)]
    [(and (.contains r "OK")
          (not (.contains r "FOUND")))
     r]))

(defn parse-reply [client reply]
  (let [[clean? msg] (scan-reply reply)]
    {:clean?  clean?
     :message reply}))

(defn file->byte-arr [file]
  (let [a  (byte-array (.length file))
        is (FileInputStream. file)]
    (.read is a)
    (.close is)
    a))

(defn scan [client is]
  (.scan client is))

(defn context->user [context]
  (let [context' (user/with-context context)
        user-fn  (some-fn :user/uuid
                          :user
                          :user/email
                          :session-uuid)
        user     (user-fn context')]
    (cond
      (string? user) user
      (number? user) user
      :else          (:user/uuid user))))

(defn prepare-semaphore-data [context data]
  (let [user (context->user context)]
    (-> data
        (update :timestamp pretty-date)
        (update :original-file (memfn getName))
        (assoc :user-uuid user))))

(defn upload-failed-file [file context]
  (let [user (context->user context)
        date (pretty-date (now) "yyyy-MM-dd HH:mm:ss")
        name (str user "/" (str date "__" (.getName file)))]
    (log/info :upload-failed-file name)
    (snake/upload! config/S3-QUARANTINED-MEDIA-BUCKET name file)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public functions

(defn report-error
  "Reports an error to the specified report email address.

   `result`       -> the result of scanning the file.
   `context`      -> additional information / context to give.
   `report-email` -> email address to send to.

   Will save a pending message for `semaphore` to process."
  [result context report-email]
  (log/warn "CLAMAV: failed scan!" :scan-result result)
  (->> result
       (prepare-semaphore-data context)
       (semaphore/save-pending-message! "clamav-report"))
  result)

(defn scan-file
  "Scans the specified file with ClamAV. Will only scan if
   `config/PRODUCTION?` is true.

   Returns a map, containing the following keys:
   `:clean?`        -> whether or not the file is clean.
   `:original-file` -> the original file given.
   `:message`       -> a message that can be shown to the user.

   Note: when `:clean?` is nil, then the file was either nil or had no content."
  [file]
  (-> (if config/PRODUCTION?
        (let [client (client)]
          (if (< 0 (.length file))
            (assoc (->> file
                        file->byte-arr
                        (scan client)
                        (parse-reply client)))
            {:clean?  nil
             :message "Empty or no file given."}))
        {:clean?  true
         :message "File scanned successfully! (Scanning disabled in staging / testing)"})
      (merge {:timestamp     (now)
              :original-file file})))

(defn scan-with-report!
  "Scans the given file with ClamAV. Will report (via email) any
   failed scans, using the context as data to report on.

   Returns the same result as `scan-file`.

   Optional email can be given, which is where the report is sent to. Defaults
   `config/CLAM-AV-REPORT-EMAIL`."
  ([file context] (scan-with-report! file context config/CLAM-AV-REPORT-EMAIL))
  ([file context report-email]
   (let [result (assoc (scan-file file) :report-email report-email)]
     (when (not (:clean? result))
       (report-error result context report-email)
       (upload-failed-file file context))
     result)))

(defn new-report [files]
  {:found-files []
   :total-files (count files)
   :start-time  (now)})

(defn scan-many-with-report!
  "Scans a `seq` of files, and gives one report of all files scanned at the end."
  [files]
  (loop [fs      files
         *report (atom (new-report files))]
    (let [file   (first fs)
          result (scan-file file)]
      (when (not (:clean? result))
        (swap! *report update :found-files conj file))
      (if (empty? (rest fs))
        (swap! *report assoc :time-end (now))
        (recur (rest fs) *report)))))

(comment

  (let [file (java.io.File/createTempFile "filename" ".txt")
        bw   (BufferedWriter. (FileWriter. file))]
    (.write bw "Some ccontent.")
    (.close bw)
    (prepare-semaphore-data (scan-file file)))

  (let [files (take 10 (repeatedly #(java.io.File/createTempFile (str "file-#" (rand-int 100)) ".txt")))]
    (scan-many-with-report! files))

  )
