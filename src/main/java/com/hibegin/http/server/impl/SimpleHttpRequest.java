package com.hibegin.http.server.impl;

import com.hibegin.common.util.BytesUtil;
import com.hibegin.common.util.IOUtil;
import com.hibegin.common.util.LoggerUtil;
import com.hibegin.http.HttpMethod;
import com.hibegin.http.server.ApplicationContext;
import com.hibegin.http.server.api.HttpRequest;
import com.hibegin.http.server.config.RequestConfig;
import com.hibegin.http.server.config.ServerConfig;
import com.hibegin.http.server.execption.InternalException;
import com.hibegin.http.server.handler.ReadWriteSelectorHandler;
import com.hibegin.http.server.util.FileCacheKit;
import com.hibegin.http.server.util.PathUtil;
import com.hibegin.http.server.web.cookie.Cookie;
import com.hibegin.http.server.web.session.HttpSession;
import com.hibegin.http.server.web.session.SessionUtil;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SimpleHttpRequest implements HttpRequest {

    private static final Logger LOGGER = LoggerUtil.getLogger(SimpleHttpRequest.class);
    private Cookie[] cookies;
    private HttpSession session;
    private RequestConfig requestConfig;
    private ApplicationContext applicationContext;
    private Map<String, Object> attr;
    private ReadWriteSelectorHandler handler;
    private long createTime;
    private InputStream inputStream;

    protected Map<String, String> header = new HashMap<>();
    protected Map<String, String[]> paramMap;
    protected String uri;
    protected String queryStr;
    protected HttpMethod method;
    protected Map<String, File> files;
    protected File tmpRequestBodyFile;
    protected String requestHeaderStr;

    protected SimpleHttpRequest(ReadWriteSelectorHandler handler, ApplicationContext applicationContext, RequestConfig requestConfig) {
        this.requestConfig = requestConfig;
        this.createTime = System.currentTimeMillis();
        this.handler = handler;
        this.applicationContext = applicationContext;
    }

    @Override
    public Map<String, String[]> getParamMap() {
        return paramMap;
    }

    @Override
    public String getHeader(String key) {
        String headerValue = header.get(key);
        if (headerValue != null) {
            return headerValue;
        }
        for (Map.Entry<String, String> entry : header.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public String getRemoteHost() {
        return ((InetSocketAddress) handler.getChannel().socket().getRemoteSocketAddress()).getHostString();
    }

    public HttpMethod getMethod() {
        return method;
    }

    @Override
    public String getUrl() {
        return getScheme() + "://" + getHeader("Host") + uri;
    }

    @Override
    public String getRealPath() {
        return PathUtil.getStaticPath();
    }

    @Override
    public Cookie[] getCookies() {
        if (cookies == null) {
            dealWithCookie(false);
        }
        if (cookies == null) {
            //avoid not happen NullPointException
            cookies = new Cookie[0];
        }
        return cookies;
    }

    @Override
    public HttpSession getSession() {
        if (session == null) {
            dealWithCookie(true);
        }
        return session;
    }

    private void dealWithCookie(boolean create) {
        if (!requestConfig.isDisableCookie()) {
            String cookieHeader = getHeader("Cookie");
            if (cookieHeader != null) {
                cookies = Cookie.saxToCookie(cookieHeader);
                String jsessionid = Cookie.getJSessionId(cookieHeader, getServerConfig().getSessionId());
                if (jsessionid != null) {
                    session = SessionUtil.getSessionById(jsessionid);
                }
            }
            if (create && session == null) {
                if (cookies == null) {
                    cookies = new Cookie[1];
                } else {
                    cookies = new Cookie[cookies.length + 1];
                }
                Cookie cookie = new Cookie(true);
                String jsessionid = UUID.randomUUID().toString();
                cookie.setName(getServerConfig().getSessionId());
                cookie.setPath("/");
                cookie.setValue(jsessionid);
                cookies[cookies.length - 1] = cookie;
                session = new HttpSession(jsessionid);
                SessionUtil.sessionMap.put(jsessionid, session);
                //LOGGER.info("create a cookie " + cookie.toString());
            }
        }
    }

    @Override
    public String getParaToStr(String key) {
        if (paramMap.get(key) != null) {
            try {
                return URLDecoder.decode(paramMap.get(key)[0], requestConfig.getCharSet());
            } catch (UnsupportedEncodingException e) {
                LOGGER.log(Level.SEVERE, "", e);
            }
        }
        return null;
    }

    @Override
    public File getFile(String key) {
        if (files != null) {
            return files.get(key);
        }
        return null;
    }

    @Override
    public int getParaToInt(String key) {
        if (paramMap.get(key) != null) {
            return Integer.parseInt(paramMap.get(key)[0]);
        }
        return 0;
    }

    @Override
    public boolean getParaToBool(String key) {
        return paramMap.get(key) != null && ("on".equals(paramMap.get(key)[0]) || "true".equals(paramMap.get(key)[0]));
    }

    @Override
    public String getUri() {
        return uri;
    }

    @Override
    public String getFullUrl() {
        if (queryStr != null) {
            return getUrl() + "?" + queryStr;
        }
        return getUrl();
    }

    @Override
    public String getQueryStr() {
        return queryStr;
    }

    @Override
    public Map<String, Object> getAttr() {
        if (attr == null) {
            attr = Collections.synchronizedMap(new HashMap<String, Object>());
        }
        return attr;
    }

    @Override
    public String getScheme() {
        return requestConfig.isSsl() ? "https" : "http";
    }

    @Override
    public Map<String, String> getHeaderMap() {
        return header;
    }

    @Override
    public InputStream getInputStream() {
        if (inputStream != null) {
            return inputStream;
        } else {
            if (tmpRequestBodyFile != null) {
                try {
                    inputStream = new FileInputStream(tmpRequestBodyFile);
                } catch (FileNotFoundException e) {
                    //e.printStackTrace();
                    throw new RuntimeException(e);
                }
            } else {
                inputStream = new ByteArrayInputStream(new byte[]{});
            }
            return inputStream;
        }
    }

    @Override
    public RequestConfig getRequestConfig() {
        return requestConfig;
    }

    public Map<String, String[]> decodeParamMap() {
        Map<String, String[]> encodeMap = new HashMap<>();
        for (Map.Entry<String, String[]> entry : getParamMap().entrySet()) {
            String[] strings = new String[entry.getValue().length];
            for (int i = 0; i < entry.getValue().length; i++) {
                try {
                    strings[i] = URLDecoder.decode(entry.getValue()[i], "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    LOGGER.log(Level.SEVERE, "decode error", e);
                }
            }
            encodeMap.put(entry.getKey(), strings);
        }
        return encodeMap;
    }

    @Override
    public ReadWriteSelectorHandler getHandler() {
        return handler;
    }

    public long getCreateTime() {
        return createTime;
    }

    public ByteBuffer getInputByteBuffer() {
        if (getRequestConfig().isRecordRequestBody()) {
            byte[] splitBytes = HttpRequestDecoderImpl.SPLIT.getBytes();
            byte[] bytes = requestHeaderStr.getBytes();
            if (tmpRequestBodyFile == null) {
                ByteBuffer buffer = ByteBuffer.allocate(bytes.length + splitBytes.length);
                buffer.put(bytes);
                buffer.put(splitBytes);
                return buffer;
            } else {
                byte[] dataBytes = new byte[0];
                try {
                    dataBytes = IOUtil.getByteByInputStream(new FileInputStream(tmpRequestBodyFile));
                } catch (FileNotFoundException e) {
                    //e.printStackTrace();
                }
                ByteBuffer buffer = ByteBuffer.allocate(requestHeaderStr.getBytes().length + splitBytes.length + dataBytes.length);
                buffer.put(requestHeaderStr.getBytes());
                buffer.put(splitBytes);
                buffer.put(dataBytes);
                return buffer;
            }
        } else {
            throw new InternalException("Please enable record request body");
        }
    }

    @Override
    public ServerConfig getServerConfig() {
        return getApplicationContext().getServerConfig();
    }

    @Override
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    @Override
    public ByteBuffer getRequestBodyByteBuffer() {
        return getRequestBodyByteBuffer(0);
    }

    @Override
    public ByteBuffer getRequestBodyByteBuffer(int offset) {
        try {
            if (tmpRequestBodyFile != null && offset <= tmpRequestBodyFile.length()) {
                byte[] bytes = IOUtil.getByteByInputStream(new FileInputStream(tmpRequestBodyFile.toString()));
                return ByteBuffer.wrap(BytesUtil.subBytes(bytes, offset, bytes.length - offset));
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "", e);
            //throw new InternalException(e);
        }
        return ByteBuffer.wrap(new byte[0]);
    }

    public void deleteTempUploadFiles() {
        if (tmpRequestBodyFile != null) {
            FileCacheKit.deleteCache(tmpRequestBodyFile);
        }
        if (files != null) {
            for (File file : files.values()) {
                FileCacheKit.deleteCache(file);
            }
        }
    }

    @Override
    public String getHttpVersion() {
        String[] tempArr = requestHeaderStr.split("\r\n");
        if (tempArr.length > 0) {
            if (tempArr[0].split(" ").length > 2) {
                return tempArr[0].split(" ")[2];
            }
        }
        return "";
    }
}
