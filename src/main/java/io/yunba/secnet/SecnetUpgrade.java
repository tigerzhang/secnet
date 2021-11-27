package io.yunba.secnet;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.configurationprocessor.json.JSONArray;
import org.springframework.boot.configurationprocessor.json.JSONException;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

import static java.lang.System.currentTimeMillis;

@Configuration
@PropertySource(value = {"classpath:application.properties"},encoding="gbk")
@RestController
public class SecnetUpgrade {
    TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[]{};
                }
            }
    };

    @Autowired
    Environment environment;

    @Value("${secnet.upgrade.addr}")
    String URL_BASE;

    @Value("${secnet.upgrade.appid}")
    String appid;

    @Value("${secnet.upgrade.appkey}")
    String appkey;

    final Boolean TEST_DATA = false;
    Logger logger = Logger.getLogger("SecnetUpgrade");

    JSONArray get_list(int page, int pageSize) throws IOException, JSONException, NoSuchAlgorithmException, KeyManagementException {
        if (TEST_DATA) {
            if (page == 1) {
                String testData = "{\"devices\":[{\"createTime\":\"20211020T025237Z\",\"deviceId\":\"D5647491237is8Cy\",\"deviceInfo\":{\"description\":\"\",\"deviceType\":\"Gateway\",\"manufacturerId\":\"kit_20211020\",\"manufacturerName\":\"kit\",\"model\":\"test-20211020\",\"mute\":\"FALSE\",\"name\":\"device001\",\"nodeId\":\"SN-40-42726-adee04\",\"protocolType\":\"MQTT\",\"status\":\"OFFLINE\"},\"deviceOid\":491237,\"gatewayId\":\"\",\"nodeType\":\"GATEWAY\",\"tags\":[]}],\"pageNo\":1,\"pageSize\":1,\"totalCount\":1818}";
                JSONObject j = new JSONObject(testData);
                return j.getJSONArray("devices");
            } else {
                return new JSONArray();
            }
        } else {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            String timestamp = String.format("%d", currentTimeMillis());
            String auth = get_auth(appid, appkey, timestamp);
            logger.info("timestamp " + timestamp);
            logger.info("auth " + auth);
            String url = String.format("%s/iot/1.0/devicesList?appId=%s&pageSize=%d&pageNo=%d", URL_BASE, appid, pageSize, page);
            logger.info("url " + url);
            OkHttpClient client = new OkHttpClient().newBuilder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
            Request request = new Request.Builder()
                    .url(url)
                    .method("GET", null)
                    .addHeader("timestamp", timestamp)
                    .addHeader("Authorization", auth)
                    .build();
            Response response = client.newCall(request).execute();
            String bodyStr = response.body().string();
            logger.info(bodyStr);
            JSONArray devices = new JSONArray();
            if (response.isSuccessful()) {
                JSONObject jsonObject = new JSONObject(bodyStr);
                devices = jsonObject.getJSONArray("devices");
            }
            return devices;
        }
    }

    private String get_auth(String appid, String appkey, String timestamp) {
        String s = appid + appkey + timestamp;

        logger.info("auth origin " + s);

        return DigestUtils.sha256Hex(s);
    }

    int currentPage = 0;

    @GetMapping("/upgrade/{start}/{pageSize}")
    ResponseEntity<StreamingResponseBody> upgrade(@PathVariable int start, @PathVariable int pageSize) {
        currentPage = start;
        StreamingResponseBody responseBody = response -> {
            while (true) {
                JSONArray devices;
                try {
                    devices = get_list(currentPage, pageSize);
                    currentPage += 1;
                    if (devices.length() == 0) {
                        response.write("Done".getBytes());
                        response.flush();
                        break;
                    }

                    for (int i=0; i<devices.length(); i++) {
                        JSONObject device = (JSONObject) devices.get(i);
                        String deviceId = device.getString("deviceId");
                        String status = device.getJSONObject("deviceInfo").getString("status");

                        response.write(String.format("%s %s\n", deviceId, status).getBytes());
                        response.flush();

                        if (status.equals("ONLINE")) {
//                            upgrade_firmware();
                        }
                    }
                } catch (JSONException | NoSuchAlgorithmException | KeyManagementException e) {
                    e.printStackTrace();
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(responseBody);
    }
}
