package io.hydrosphere.ambari.view;

import org.apache.ambari.view.ViewContext;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketClient;
import org.eclipse.jetty.websocket.WebSocketClientFactory;
import org.eclipse.jetty.websocket.WebSocketFactory;
import org.mitre.dsmiley.httpproxy.ProxyServlet;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Simple servlet for hello view.
 */
public class AmbariProxyServlet extends ProxyServlet implements WebSocketFactory.Acceptor {

    private static final String URI_REPLACE_VALUE = "uriReplaceValue";
    private static final String URI_REPLACE_PATTERN = "uriReplacePattern";

    private String uriReplacePattern = "/proxied/";
    private String uriReplaceValue = "/";

    private WebSocketClientFactory factory = new WebSocketClientFactory();
    private WebSocketFactory _webSocketFactory;

    @Override
    protected String getTargetUri(HttpServletRequest servletRequest) {
        String res = super.getTargetUri(servletRequest);
        return res.replace(uriReplacePattern, uriReplaceValue);
    }

    @Override
    public void init() throws ServletException {
        try {
            factory.start();
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServletException(e);
        }
        try {
            String bs = getInitParameter("bufferSize");
            _webSocketFactory = new WebSocketFactory(this, bs == null ? 8192 : Integer.parseInt(bs));
            _webSocketFactory.start();

            String max = getInitParameter("maxIdleTime");
            if (max != null)
                _webSocketFactory.setMaxIdleTime(Integer.parseInt(max));

            max = getInitParameter("maxTextMessageSize");
            if (max != null)
                _webSocketFactory.setMaxTextMessageSize(Integer.parseInt(max));

            max = getInitParameter("maxBinaryMessageSize");
            if (max != null)
                _webSocketFactory.setMaxBinaryMessageSize(Integer.parseInt(max));

            String min = getInitParameter("minVersion");
            if (min != null)
                _webSocketFactory.setMinVersion(Integer.parseInt(min));
        } catch (ServletException x) {
            x.printStackTrace();
            throw x;
        } catch (Exception x) {
            x.printStackTrace();
            throw new ServletException(x);
        }

        super.init();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            if (_webSocketFactory.acceptWebSocket(request, response) || response.isCommitted())
                return;
            super.service(request, response);
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServletException(e);
        }
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        ServletContext context = config.getServletContext();
        ViewContext viewContext = (ViewContext) context.getAttribute(ViewContext.CONTEXT_ATTRIBUTE);

        uriReplacePattern = config.getInitParameter(URI_REPLACE_PATTERN);
        uriReplaceValue = config.getInitParameter(URI_REPLACE_VALUE);

        super.init(new ProxiedServletConfig(viewContext, config));
    }

    @Override
    public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol) {
        String uri = request.getRequestURI();
        return new AmbariProxyServlet.BinaryInboundWebSocket(targetHost,
                uri.substring(uri.indexOf(request.getPathInfo())));
    }

    @Override
    public boolean checkOrigin(HttpServletRequest request, String origin) {
        return true;
    }

    @Override
    public void destroy() {
        try {
            _webSocketFactory.stop();
        } catch (Exception x) {
            x.printStackTrace();
        }
        super.destroy();
    }

    private class ProxiedServletConfig implements ServletConfig {

        private final ViewContext viewContext;

        private final ServletConfig servletConfig;

        private ProxiedServletConfig(ViewContext viewContext, ServletConfig servletConfig) {
            this.viewContext = viewContext;
            this.servletConfig = servletConfig;
        }

        public String getServletName() {
            return servletConfig.getServletName();
        }

        public ServletContext getServletContext() {
            return servletConfig.getServletContext();
        }

        public String getInitParameter(String s) {
            String value = null;
            if (viewContext != null && viewContext.getProperties() != null) {
                value = viewContext.getProperties().get(s);
            }
            if (value == null) {
                value = servletConfig.getInitParameter(s);
            }
            if (value == null && s.equals("targetUri")) {
                value = "http://54.154.5.76:2004";
            }
            return value;
        }

        public Enumeration getInitParameterNames() {
            HashSet params = new HashSet();
            if (viewContext != null && viewContext.getProperties() != null) {
                params.addAll(viewContext.getProperties().keySet());
            }
            if (servletConfig.getInitParameterNames() != null) {
                for (Enumeration e = servletConfig.getInitParameterNames(); e.hasMoreElements(); )
                    params.add(e.nextElement());
            }
            params.add("targetUri");
            return new ProxiedEnumerator(params.iterator());
        }
    }

    private boolean isIndexFile(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String realPath = uri.substring(uri.indexOf(request.getPathInfo()));
        return realPath.startsWith("/ui/")
                && !realPath.endsWith(".css")
                && !realPath.endsWith(".js")
                && !realPath.endsWith(".ico")
                && !realPath.endsWith(".png")
                && !realPath.endsWith(".img")
                && !realPath.endsWith(".map");
    }

    @Override
    protected void copyResponseEntity(HttpResponse proxyResponse, HttpServletResponse servletResponse,
                                      HttpRequest proxyRequest, HttpServletRequest servletRequest) throws IOException {

        if (isIndexFile(servletRequest)) {
            HttpEntity entity = proxyResponse.getEntity();
            if (entity != null) {
                String uri = servletRequest.getRequestURI();
                String indexPath = uri.substring(0, uri.indexOf(servletRequest.getPathInfo())) + "/ui";


                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                try {
                    entity.writeTo(stream);

                    String str = stream.toString("UTF-8");
                    str = str.replace("<base href=\"/ui/\">", "<base href=\"" + indexPath + "/\">");

                    byte[] bytesToWrite = str.getBytes();
                    servletResponse.setContentLength(bytesToWrite.length);
                    OutputStream servletOutputStream = servletResponse.getOutputStream();
                    servletOutputStream.write(bytesToWrite);
                    servletOutputStream.flush();
                } finally {
                    stream.close();
                }
            }
        } else {
            super.copyResponseEntity(proxyResponse, servletResponse, proxyRequest, servletRequest);
        }
    }

    private class ProxiedEnumerator implements Enumeration {
        private final Iterator<Object> iterator;

        private ProxiedEnumerator(Iterator<Object> iterator) {
            this.iterator = iterator;
        }

        public boolean hasMoreElements() {
            return iterator.hasNext();
        }

        public Object nextElement() {
            return iterator.next();
        }
    }


    private class BinaryInboundWebSocket implements WebSocket.OnBinaryMessage, WebSocket.OnTextMessage {
        private final HttpHost destination;

        private final String path;

        private Connection outbound;

        private final WebSocketClient client = factory.newWebSocketClient();

        public BinaryInboundWebSocket(HttpHost destination, String path) {
            this.destination = destination;
            this.path = path;
        }

        @Override
        public void onMessage(byte[] data, int offset, int length) {
            try {
                outbound.sendMessage(data, offset, length);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onOpen(Connection connection) {
            try {
                outbound = client.open(new URI("ws://" + destination.getHostName() + ":" + destination.getPort() + path),
                        new AmbariProxyServlet.BinaryOutboundWebSocket(connection))
                        .get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onClose(int closeCode, String message) {
            if (outbound != null)
                outbound.close(closeCode, message);
        }

        @Override
        public void onMessage(String data) {
            try {
                outbound.sendMessage(data);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    private class BinaryOutboundWebSocket implements WebSocket.OnBinaryMessage, WebSocket.OnTextMessage {

        private final Connection inbound;

        private BinaryOutboundWebSocket(Connection inbound) {
            this.inbound = inbound;
        }

        @Override
        public void onMessage(byte[] data, int offset, int length) {
            try {
                inbound.sendMessage(data, offset, length);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onOpen(Connection connection) {
        }

        @Override
        public void onClose(int closeCode, String message) {
            if (inbound != null)
                inbound.close(closeCode, message);
        }

        @Override
        public void onMessage(String data) {
            try {
                inbound.sendMessage(data);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }
}