(ns clams.core
  (:require [snake.core :as snake])
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

(defn client [config]
  (if @*av-client
    @*av-client
    (let [host (:clams/host config)
          port (Integer/parseInt (:clams/port config))]
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

(defn upload-failed-file [file context config]
  (let [bucket  (:clams/bucket config)
        name-fn (:clams/name-fn config)
        date    (pretty-date (now) "yyyy-MM-dd HH:mm:ss")
        name    (str (name-fn result config context) "/" (str date "__" (.getName file)))]
    (log/info :upload-failed-file name)
    (snake/upload! bucket name file)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public functions

(defn report-error
  "Reports an error to the specified report email address.

   `result`       -> the result of scanning the file.
   `context`      -> additional information / context to give.
   `report-email` -> email address to send to.

   Will save a pending message for `semaphore` to process."
  [result context config]
  (let [error-fn  (or (:clams/error-fn config)
                      (partial log/warn))
        report-fn (or (:clams/report-fn config)
                      (partial log/error))])
  (error-fn result config)
  (report-fn result config)
  result)

(defn scan-file
  "Scans the specified file with ClamAV. Will only scan if
   `config/PRODUCTION?` is true.

   Returns a map, containing the following keys:
   `:clean?`        -> whether or not the file is clean.
   `:original-file` -> the original file given.
   `:message`       -> a message that can be shown to the user.

   Note: when `:clean?` is nil, then the file was either nil or had no content."
  [file context config]
  (-> (let [client (client config)]
        (if (< 0 (.length file))
          (assoc (->> file
                      file->byte-arr
                      (scan client)
                      (parse-reply client)))
          {:clean?  nil
           :message "Empty or no file given."}))
      (merge {:timestamp     (now)
              :original-file file})))

(defn scan-with-report!
  "Scans the given file with ClamAV. Will report (via email) any
   failed scans, using the context as data to report on.

   Returns the same result as `scan-file`.

   Optional email can be given, which is where the report is sent to. Defaults
   `config/CLAM-AV-REPORT-EMAIL`."
  [file context config]
  (let [result (assoc (scan-file file) :report-email report-email)]
    (when (not (:clean? result))
      (report-error result context config)
      (upload-failed-file file context config))
    result))

(defn new-report [files]
  {:found-files []
   :total-files (count files)
   :start-time  (now)})

(defn scan-many-with-report!
  "Scans a `seq` of files, and gives one report of all files scanned at the end."
  [files context config]
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
    (.close bw))

  (let [files (take 10 (repeatedly #(java.io.File/createTempFile (str "file-#" (rand-int 100)) ".txt")))]
    (scan-many-with-report! files))

  )
