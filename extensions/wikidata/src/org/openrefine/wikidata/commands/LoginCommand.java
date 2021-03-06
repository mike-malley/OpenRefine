/*******************************************************************************
 * MIT License
 *
 * Copyright (c) 2018 Antonin Delpeuch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/
package org.openrefine.wikidata.commands;

import com.google.refine.commands.Command;
import org.wikidata.wdtk.wikibaseapi.ApiConnection;
import org.wikidata.wdtk.wikibaseapi.BasicApiConnection;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpCookie;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

/**
 * Handles login.
 * <p>
 * Both logging in with username/password or owner-only consumer are supported.
 * <p>
 * This command also manages cookies of login credentials.
 */
public class LoginCommand extends Command {

    static final String WIKIDATA_COOKIE_PREFIX = "openrefine-wikidata-";

    static final String WIKIBASE_USERNAME_COOKIE_KEY = "wikibase-username";

    static final String USERNAME = "wb-username";
    static final String PASSWORD = "wb-password";

    static final String CONSUMER_TOKEN = "wb-consumer-token";
    static final String CONSUMER_SECRET = "wb-consumer-secret";
    static final String ACCESS_TOKEN = "wb-access-token";
    static final String ACCESS_SECRET = "wb-access-secret";

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        ConnectionManager manager = ConnectionManager.getInstance();

        if ("true".equals(request.getParameter("logout"))) {
            manager.logout();
            removeUsernamePasswordCookies(request, response);
            removeOwnerOnlyConsumerCookies(request, response);
            respond(request, response);
            return; // return directly
        }

        boolean remember = "on".equals(request.getParameter("remember-credentials"));

        // Credentials from parameters have higher priority than those from cookies.
        String username = request.getParameter(USERNAME);
        String password = request.getParameter(PASSWORD);
        String consumerToken = request.getParameter(CONSUMER_TOKEN);
        String consumerSecret = request.getParameter(CONSUMER_SECRET);
        String accessToken = request.getParameter(ACCESS_TOKEN);
        String accessSecret = request.getParameter(ACCESS_SECRET);

        if (isBlank(username) && isBlank(password) && isBlank(consumerToken) &&
                isBlank(consumerSecret) && isBlank(accessToken) && isBlank(accessSecret)) {
            // In this case, we use cookie to login, and we will always remember the credentials in cookies.
            remember = true;
            Cookie[] cookies = request.getCookies();

            for (Cookie cookie : cookies) {
                String value = getCookieValue(cookie);
                switch (cookie.getName()) {
                    case CONSUMER_TOKEN:
                        consumerToken = value;
                        break;
                    case CONSUMER_SECRET:
                        consumerSecret = value;
                        break;
                    case ACCESS_TOKEN:
                        accessToken = value;
                        break;
                    case ACCESS_SECRET:
                        accessSecret = value;
                        break;
                    default:
                        break;
                }
            }

            if (isBlank(consumerToken) && isBlank(consumerSecret) && isBlank(accessToken) && isBlank(accessSecret)) {
                // Try logging in with the cookies of a password-based connection.
                String username1 = null;
                List<Cookie> cookieList = new ArrayList<>();
                for (Cookie cookie : cookies) {
                    if (cookie.getName().startsWith(WIKIDATA_COOKIE_PREFIX)) {
                        String cookieName = cookie.getName().substring(WIKIDATA_COOKIE_PREFIX.length());
                        Cookie newCookie = new Cookie(cookieName, getCookieValue(cookie));
                        cookieList.add(newCookie);
                    } else if (cookie.getName().equals(WIKIBASE_USERNAME_COOKIE_KEY)) {
                        username1 = getCookieValue(cookie);
                    }
                }

                if (cookieList.size() > 0 && username1 != null) {
                    removeOwnerOnlyConsumerCookies(request, response);
                    if (manager.login(username1, cookieList)) {
                        respond(request, response);
                        return;
                    } else {
                        removeUsernamePasswordCookies(request, response);
                    }
                }
            }
        }

        if (isNotBlank(username) && isNotBlank(password)) {
            // Once logged in with new credentials,
            // the old credentials in cookies should be cleared.
            if (manager.login(username, password) && remember) {
                ApiConnection connection = manager.getConnection();
                List<HttpCookie> cookies = ((BasicApiConnection) connection).getCookies();
                for (HttpCookie cookie : cookies) {
                    setCookie(response, WIKIDATA_COOKIE_PREFIX + cookie.getName(), cookie.getValue());
                }

                // Though the cookies from the connection contain some cookies of username,
                // we cannot make sure that all Wikibase instances use the same cookie key
                // to retrieve the username. So we choose to set the username cookie with our own cookie key.
                setCookie(response, WIKIBASE_USERNAME_COOKIE_KEY, connection.getCurrentUser());
            } else {
                removeUsernamePasswordCookies(request, response);
            }
            removeOwnerOnlyConsumerCookies(request, response);
        } else if (isNotBlank(consumerToken) && isNotBlank(consumerSecret) && isNotBlank(accessToken) && isNotBlank(accessSecret)) {
            if (manager.login(consumerToken, consumerSecret, accessToken, accessSecret) && remember) {
                setCookie(response, CONSUMER_TOKEN, consumerToken);
                setCookie(response, CONSUMER_SECRET, consumerSecret);
                setCookie(response, ACCESS_TOKEN, accessToken);
                setCookie(response, ACCESS_SECRET, accessSecret);
            } else {
                removeOwnerOnlyConsumerCookies(request, response);
            }
            removeUsernamePasswordCookies(request, response);
        }

        respond(request, response);
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        respond(request, response);
    }

    protected void respond(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ConnectionManager manager = ConnectionManager.getInstance();
        Map<String, Object> jsonResponse = new HashMap<>();
        jsonResponse.put("logged_in", manager.isLoggedIn());
        jsonResponse.put("username", manager.getUsername());
        respondJSON(response, jsonResponse);
    }

    private static void removeUsernamePasswordCookies(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        for (Cookie cookie : cookies) {
            if (cookie.getName().startsWith(WIKIDATA_COOKIE_PREFIX)) {
                removeCookie(response, cookie.getName());
            }
        }
        removeCookie(response, WIKIBASE_USERNAME_COOKIE_KEY);
    }

    private static void removeOwnerOnlyConsumerCookies(HttpServletRequest request, HttpServletResponse response) {
        removeCookie(response, CONSUMER_TOKEN);
        removeCookie(response, CONSUMER_SECRET);
        removeCookie(response, ACCESS_TOKEN);
        removeCookie(response, ACCESS_SECRET);
    }

    static String getCookieValue(Cookie cookie) throws UnsupportedEncodingException {
        return URLDecoder.decode(cookie.getValue(), "utf-8");
    }

    private static void setCookie(HttpServletResponse response, String key, String value) throws UnsupportedEncodingException {
        String encodedValue = URLEncoder.encode(value, "utf-8");
        Cookie cookie = new Cookie(key, encodedValue);
        cookie.setMaxAge(60 * 60 * 24 * 365); // a year
        cookie.setPath("/");
        // set to false because OpenRefine doesn't require HTTPS
        cookie.setSecure(false);
        response.addCookie(cookie);
    }

    private static void removeCookie(HttpServletResponse response, String key) {
        Cookie cookie = new Cookie(key, "");
        cookie.setMaxAge(0); // 0 causes the cookie to be deleted
        cookie.setPath("/");
        cookie.setSecure(false);
        response.addCookie(cookie);
    }
}
