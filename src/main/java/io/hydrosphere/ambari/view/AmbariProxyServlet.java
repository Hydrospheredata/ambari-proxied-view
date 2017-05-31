package io.hydrosphere.ambari.view;

import org.apache.ambari.view.ViewContext;
import org.mitre.dsmiley.httpproxy.ProxyServlet;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Simple servlet for hello view.
 */
public class AmbariProxyServlet extends ProxyServlet {

    private static final String URI_REPLACE_VALUE = "uriReplaceValue";
    private static final String URI_REPLACE_PATTERN = "uriReplacePattern";

    private String uriReplacePattern = "/proxied/";
    private String uriReplaceValue = "/";

    @Override
    protected String getTargetUri(HttpServletRequest servletRequest) {
        String res = super.getTargetUri(servletRequest);
        return res.replace(uriReplacePattern, uriReplaceValue);
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        ServletContext context = config.getServletContext();
        ViewContext viewContext = (ViewContext) context.getAttribute(ViewContext.CONTEXT_ATTRIBUTE);

        uriReplacePattern = config.getInitParameter(URI_REPLACE_PATTERN);
        uriReplaceValue = config.getInitParameter(URI_REPLACE_VALUE);

        super.init(new ProxiedServletConfig(viewContext, config));
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
            return new ProxiedEnumerator(params.iterator());
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
}