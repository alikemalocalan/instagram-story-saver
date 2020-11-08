package com.github.alikemalocalan.instastorysaver;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SerializableCookieJar implements CookieJar, Serializable {

    private static final long serialVersionUID = -837498359387593793L;

    Map<String, List<SerializableCookie>> map = new HashMap<>();

    @Override
    public List<Cookie> loadForRequest(HttpUrl arg0) {
        return map.getOrDefault(arg0.host(), new ArrayList<>()).stream()
                .map(c -> c.cookie)
                .collect(Collectors.toList());
    }

    @Override
    public void saveFromResponse(HttpUrl arg0, List<Cookie> arg1) {
        List<SerializableCookie> list =
                arg1.stream().map(SerializableCookie::new).collect(Collectors.toList());
        if (map.containsKey(arg0.host())) {
            map.get(arg0.host()).addAll(list);
        } else map.put(arg0.host(), list);
    }


    public static class SerializableCookie implements Serializable {

        public SerializableCookie(Cookie cookie) {
            this.cookie = cookie;
        }

        private static final long serialVersionUID = -8594045714036645534L;

        private transient Cookie cookie;

        private void writeObject(ObjectOutputStream out) throws IOException {
            Utils.writeObject(cookie, out);
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            cookie = Utils.readObject(in);
        }

    }

}