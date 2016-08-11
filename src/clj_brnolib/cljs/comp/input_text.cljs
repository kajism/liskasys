(ns clj-brnolib.cljs.comp.input-text
  (:require [re-com.core :as re-com]
            [re-frame.core :as re-frame]))

(defn input-text [item ent-kw attr-kw conversion-fn]
  (let [error-msg (some-> item :-errors attr-kw)
        conversion-fn (or conversion-fn identity)]
    [re-com/input-text
     :model (str (attr-kw item))
     :on-change #(re-frame/dispatch [:entity-change ent-kw (:id item) attr-kw (conversion-fn %)])
     :status (when error-msg :warning)
     :status-icon? true
     :status-tooltip error-msg]))

