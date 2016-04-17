(ns liskasys.cljs.ajax
  (:require [ajax.core :as ajax]
            [cljs.pprint :refer [pprint]]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as timbre]))

(defn server-call
  ([request-msg response-msg]
   (server-call request-msg nil response-msg))
  ([request-msg file response-msg]
   (server-call request-msg file response-msg nil))
  ([request-msg file response-msg rollback-db]
   (ajax/POST "/api"
       (merge
        {:headers {;;"Accept" "application/transit+json"
                   "x-csrf-token" (timbre/spy (some->
                                               (.getElementById js/document "__anti-forgery-token")
                                               .-value))}
         :handler #(when response-msg (re-frame/dispatch (conj response-msg %)))
         :error-handler #(re-frame/dispatch [:set-msg :error
                                             (or (get-in % [:parse-error :original-text])
                                                 "Server je nedostupný")
                                             rollback-db])
         :response-format (ajax/transit-response-format)}
        (if file
          {:body
           (doto (js/FormData.)
             (.append "req-msg" request-msg)
             (.append "file" file))
           :format :raw}
          {:params {:req-msg request-msg}
           :format (ajax/transit-request-format)})))
   nil))
