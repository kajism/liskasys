(ns liskasys.cljc.util)

(defn person-fullname [{:keys [:person/lastname :person/firstname]}]
  (str lastname " " firstname))
