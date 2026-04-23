(ns example-app.views
  (:require
   [ring.util.anti-forgery :refer [anti-forgery-field]]
   [hiccup.core :refer [html]]))

(defn- layout [& body]
  (html
   [:html
    [:head
     [:title "Turvata Example App"]
     [:style "body { font-family: sans-serif; max-width: 600px; margin: 2rem auto; }"]]
    [:body body]]))

(defn login-page [{:keys [next params]}]
  (let [error (:error params)]
    (layout
     [:h1 "Login"]
     (when error [:p {:style "color:red"} (str "Error: " error)])
     [:form {:method "POST" :action "/auth/login"}
      (anti-forgery-field)
      [:p [:label "User ID (UUID): " [:br] [:input {:name "user-id" :size 40}]]]
      [:p [:label "Token (V2): "     [:br] [:input {:name "token" :size 60}]]]
      (when next [:input {:type "hidden" :name "next" :value next}])
      [:button {:type "submit"} "Login"]])))

(defn confirm-logout [_request]
  (layout
   [:h1 "Logout?"]
   [:form {:method "POST" :action "/auth/logout"}
    (anti-forgery-field)
    [:button {:type "submit"} "Logout"]]))

(defn logout-success [_request]
  (layout
   [:h1 "Logged out"]
   [:p [:a {:href "/auth/login"} "Log in again"]]))

(defn admin-home [request]
  (layout
   [:h1 "Admin"]
   [:p "Logged in as: " [:code (pr-str (:user-id request))]]
   [:p [:a {:href "/auth/logout"} "Logout"]]))

(defn home [_request]
  (layout
   [:h1 "Example App"]
   [:ul
    [:li [:a {:href "/auth/login"} "Login"]]
    [:li [:a {:href "/admin"} "Admin Dashboard"]]
    [:li [:a {:href "/auth/logout"} "Logout"]]]))
