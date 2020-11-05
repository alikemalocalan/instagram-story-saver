package com.github.alikemalocalan.instastorysaver;

import com.github.instagram4j.instagram4j.IGClient;
import com.github.instagram4j.instagram4j.utils.IGUtils;
import okhttp3.OkHttpClient;

import java.io.*;

public class LoginService {

    public static IGClient serializeLogin(String username, String password)
            throws ClassNotFoundException,
            IOException {
        File to = new File("/tmp/igclient.ser");
        File cookFile = new File("/tmp/cookie.ser");
        if (to.exists() && cookFile.exists()) {
            System.out.println("Deserializing. . .");
            IGClient client = IGClient.from(new FileInputStream(to),
                    formTestHttpClient(deserialize(cookFile, SerializableCookieJar.class)));
            System.out.println(client.toString());
            return client;
        } else {
            SerializableCookieJar jar = new SerializableCookieJar();
            IGClient client = new IGClient.Builder().username(username).password(password)
                    .client(formTestHttpClient(jar))
                    .login();
            System.out.println("Serializing. . .");
            serialize(client, to);
            serialize(jar, cookFile);
            return client;
        }
    }

    public static void serialize(Object o, File to) throws IOException {
        FileOutputStream file = new FileOutputStream(to);
        ObjectOutputStream out = new ObjectOutputStream(file);

        out.writeObject(o);
        out.close();
        file.close();
    }

    public static <T> T deserialize(File file, Class<T> clazz) throws IOException, ClassNotFoundException {
        InputStream in = new FileInputStream(file);
        ObjectInputStream oIn = new ObjectInputStream(in);

        T t = clazz.cast(oIn.readObject());

        in.close();
        oIn.close();

        return t;
    }

    public static OkHttpClient formTestHttpClient(SerializableCookieJar jar) {
        return IGUtils.defaultHttpClientBuilder().cookieJar(jar)
                .build();
    }
}
