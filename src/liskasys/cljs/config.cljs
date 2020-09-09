(ns liskasys.cljs.config
  (:require [clojure.string :as str]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.comp.history :as history]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.util :as util]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(defn page-configs []
  (let [configs (re-frame/subscribe [:entities :config])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Základní nastavení"]
        (when-not (seq @configs)
          [re-com/hyperlink-href :label [re-com/button :label "Vytvořit"] :href (str "#/config/e")])
        [data-table
         :table-id :configs
         :rows configs
         :colls [[[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :config])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/config/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      #_[buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :config (:db/id row)])]]])
                  :none]
                 ["Dočasně uzavřeno?" :config/temp-closure?]
                 ["Název organizace" :config/org-name]
                 ["Celé URL" :config/full-url]
                 #_["Odesilatel emalů" :config/automat-email]
                 ["Konec oml." :config/cancel-time]
                 ["Obj. obědů" :config/order-time]
                 ["Obj. v prac. dny?" :config/order-workdays-only?]
                 #_["Příjemce počtu" :config/closing-msg-role]
                 #_["Období náhrad" :config/max-subst-periods]
                 #_["Nahrazovat předem?" :config/future-subst?]]]]])))

(defn page-config []
  (let [config (re-frame/subscribe [:entity-edit :config])]
    (fn []
      (let [item @config]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Základní nastavení"]
          [re-com/label :label "Školka dočasně uzavřena? (nebudou se objednávat obědy)"]
          [re-com/checkbox
           :label "ano, máme z mimořádných důvodů do odvolání zavřeno"
           :model (:config/temp-closure? item)
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/temp-closure? %])]
          [re-com/label :label "Název organizace"]
          [re-com/input-text
           :model (str (:config/org-name item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/org-name %])
           :width "200px"]
          [re-com/label :label "Odkaz na web tohoto systému (celé URL)"]
          [re-com/input-text
           :model (str (:config/full-url item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/full-url %])
           :width "200px"]
          [re-com/label :label "Odesilatel automatických emalů (když neexistuje uživatel s rolí koordinátor)"]
          [re-com/input-text
           :model (str (:config/automat-email item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/automat-email %])
           :width "200px"]
          [re-com/label :label "Zobrazovat rodičům stránku Náhrady?"]
          [re-com/checkbox
           :label "ano, budeme používat možnost náhrad za omluvené dny"
           :model (:config/substs-page? item)
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/substs-page? %])]
          [re-com/label :label "Lze zadat omluvenku s nárokem na náhradu po objednávce oběda?"]
          [re-com/checkbox
           :label "tzn. lze omlouvat a do následujícího dne do času zadaného v následujícím poli"
           :model (:config/can-cancel-after-lunch-order? item)
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/can-cancel-after-lunch-order? %])]
          [re-com/label :label "Čas konce omlouvání na dnešek [hh:mm] (změna vyžaduje restart aplikace na serveru)"]
          [re-com/input-text
           :model (str (:config/cancel-time item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/cancel-time %])
           :width "200px"
           :validation-regex #"^([012]?\d?:\d{0,2})$"]
          [re-com/label :label "Čas objednávky obědů za další den školky [hh:mm] (změna vyžaduje restart aplikace na serveru)"]
          [re-com/input-text
           :model (str (:config/order-time item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/order-time %])
           :width "200px"
           :validation-regex #"^([012]?\d?:\d{0,2})$"]
          [re-com/label :label "Objednávat obědy pouze v pracovní dny?"]
          [re-com/checkbox
           :label "tzn. objednávka na pondělí již v pátek?"
           :model (:config/order-workdays-only? item)
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/order-workdays-only? %])]
          [re-com/label :label "Role (pouze jedna) příjemce emailu s finálním počtem dětí po konci omlouvání"]
          [re-com/input-text
           :model (str (:config/closing-msg-role item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/closing-msg-role %])
           :width "200px"]
          [re-com/label :label "Role (pouze jedna) příjemce emailu s měsíčním počtem objednaných obědů na osobu"]
          [re-com/input-text
           :model (str (:config/lunch-totals-role item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/lunch-totals-role %])
           :width "200px"]
          [re-com/label :label "Role (pouze jedna) příjemce emailu s měsíčním celkovým počtem objednaných porcí a celkovou částkou ve fondu obědů"]
          [re-com/input-text
           :model (str (:config/lunch-fund-totals-role item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/lunch-fund-totals-role %])
           :width "200px"]
          [re-com/label :label "Počet předchozích platebních období, za které je možné nahrazovat omluvenky (pouze v rámci školního roku)"]
          [re-com/input-text
           :model (str (:config/max-subst-periods item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/max-subst-periods (cljc.util/parse-int %)])
           :width "200px"
           :validation-regex #"^(\d{0,2})$"]
          [re-com/label :label "Možno nahrazovat dříve než omluvený den uplyne?"]
          [re-com/checkbox
           :label "tzn. dovolím nahrazovat budoucí omluvenky?"
           :model (:config/future-subst? item)
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/future-subst? %])]
          [re-com/label :label "Zobrazovat rodičům stránku Platby?"]
          [re-com/checkbox
           :label "ano, zobrazí se menu Platby"
           :model (:config/person-bill-page? item)
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/person-bill-page? %])]
          [re-com/label :label "Odesílat rodičům email o platbě docházky?"]
          [re-com/checkbox
           :label "ano, odeslat email po zveřejnění rozpisů plateb"
           :model (:config/person-bill-email? item)
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/person-bill-email? %])]
          [re-com/label :label "Výchozí platební režim docházky"]
          [re-com/single-dropdown
           :model (some-> item :config/att-payment-months)
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/att-payment-months %])
           :choices (vals cljc.util/att-payment-choices)
           :width "400px"]
          [re-com/label :label "Stylizace (logo, barva pozadi 1, barva pozadi 2)"]
          [re-com/input-text
           :model (str (:config/styling item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/styling %])
           :width "400px"]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :config])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/configs")]]]
          [history/view (:db/id item)]]]))))

(secretary/defroute "/configs" []
  (re-frame/dispatch [:set-current-page :configs]))
(pages/add-page :configs #'page-configs)

(secretary/defroute #"/config/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :config (cljc.util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :config]))
(pages/add-page :config #'page-config)
(common/add-kw-url :config "config")
