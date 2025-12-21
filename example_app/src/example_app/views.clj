(ns example-app.views
  (:require [ring.util.anti-forgery :refer [anti-forgery-field]]))

(defn login-page [{:keys [next params]}]
  (let [error (:error params)]
    (str "<!doctype html>"
         "<html><body>"
         "<h1>Login</h1>"
         (when error (str "<p style='color:red'>Error: " error "</p>"))
         "<form method='POST' action='/auth/login'>"
         (anti-forgery-field)
         "<label>User ID: <input name='username' /></label><br/>"
         "<label>Token: <input name='token' /></label><br/>"
         (when next (str "<input type='hidden' name='next' value='" next "'/>"))
         "<button type='submit'>Login</button>"
         "</form>"
         "</body></html>")))

(defn confirm-logout [_request]
  (str "<!doctype html><html><body>"
       "<h1>Logout?</h1>"
       "<form method='POST' action='/auth/logout'>"
       (anti-forgery-field)
       "<button type='submit'>Logout</button>"
       "</form></body></html>"))

(defn logout-success [_request]
  (str "<!doctype html><html><body>"
       "<h1>Logged out</h1>"
       "<a href='/auth/login'>Log in again</a>"
       "</body></html>"))

(defn admin-home [request]
  (str "<!doctype html><html><body>"
       "<h1>Admin</h1>"
       "<p>Logged in as: " (pr-str (:user-id request)) "</p>"
       "<a href='/auth/logout'>Logout</a>"
       "</body></html>"))

(defn home [request]
  (str "<!doctype html><html><body>"
       "<h1>Example App</h1>"
       "<p><a href='/auth/logout'>Logout</a></p>"
       "<p><a href='/auth/login'>Login</a></p>"
       "</body></html>"))
