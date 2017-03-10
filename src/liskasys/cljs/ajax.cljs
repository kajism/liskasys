(ns liskasys.cljs.ajax
  (:require [ajax.core :as ajax]
            [cljs.pprint :refer [pprint]]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as timbre]))

(defn server-call
  ([request-msg response-evt]
   (server-call request-msg nil response-evt))
  ([request-msg file response-evt]
   (server-call request-msg file response-evt nil))
  ([request-msg file response-evt rollback-db]
   (ajax/POST "/admin.app/api"
       (merge
        {:headers {;;"Accept" "application/transit+json"
                   "x-csrf-token" (some->
                                   (.getElementById js/document "__anti-forgery-token")
                                   .-value)}
         :handler #(cond
                     (:error/msg %)
                     (re-frame/dispatch [:set-msg :error (:error/msg %) rollback-db])
                     response-evt
                     (re-frame/dispatch (conj response-evt %)))
         :error-handler #(re-frame/dispatch [:set-msg :error
                                             (or (get-in (timbre/spy %) [:parse-error :original-text])
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
