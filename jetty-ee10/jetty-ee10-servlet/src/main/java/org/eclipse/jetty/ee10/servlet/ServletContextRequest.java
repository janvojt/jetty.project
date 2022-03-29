//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.server.handler.ContextResponse;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServletContextRequest extends ContextRequest implements Runnable
{
    public static final String __MULTIPART_CONFIG_ELEMENT = "org.eclipse.jetty.multipartConfig";

    private static final Logger LOG = LoggerFactory.getLogger(ServletContextRequest.class);
    private static final Collection<Locale> __defaultLocale = Collections.singleton(Locale.getDefault());
    private static final int INPUT_NONE = 0;
    private static final int INPUT_STREAM = 1;
    private static final int INPUT_READER = 2;

    private static final MultiMap<String> NO_PARAMS = new MultiMap<>();
    private static final MultiMap<String> BAD_PARAMS = new MultiMap<>();

    public static ServletContextRequest getBaseRequest(ServletRequest request)
    {
        if (request instanceof ServletApiRequest)
            return ((ServletApiRequest)request).getRequest();

        Object channel = request.getAttribute(ServletChannel.class.getName());
        if (channel instanceof ServletChannel)
            return ((ServletChannel)channel).getRequest();

        while (request instanceof ServletRequestWrapper)
        {
            request = ((ServletRequestWrapper)request).getRequest();
        }

        if (request instanceof ServletApiRequest)
            return ((ServletApiRequest)request).getRequest();

        return null;
    }

    ServletChannel _servletChannel;
    final ServletApiRequest _httpServletRequest;
    final ServletHandler.MappedServlet _mappedServlet;
    ServletContextResponse _response;
    final HttpInput _httpInput;
    final String _pathInContext;
    private UserIdentity.Scope _scope;

    final List<ServletRequestAttributeListener> _requestAttributeListeners = new ArrayList<>();

    protected ServletContextRequest(
        ServletContextHandler.ServletContextApi servletContextApi,
        ServletChannel servletChannel,
        Request request,
        String pathInContext,
        ServletHandler.MappedServlet mappedServlet)
    {
        super(servletContextApi.getContextHandler(), servletContextApi.getContext(), request, pathInContext);
        _servletChannel = servletChannel;
        _httpServletRequest = new ServletApiRequest();
        _mappedServlet = mappedServlet;
        _httpInput = new HttpInput(_servletChannel); // TODO recycle
        _pathInContext = pathInContext;
    }

    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        // TODO: should we set callback here instead of using the getCallback() below.
        super.process(request, response, callback);
    }

    @Override
    protected ContextResponse newContextResponse(Request request, Response response)
    {
        _response = new ServletContextResponse(_servletChannel, this, response);
        _servletChannel.setCallback(getCallback());
        return _response;
    }

    public ServletRequestState getState()
    {
        return _servletChannel.getState();
    }

    public ServletContextResponse getResponse()
    {
        return _response;
    }

    @Override
    public ServletContextHandler.Context getContext()
    {
        return (ServletContextHandler.Context)super.getContext();
    }

    public HttpInput getHttpInput()
    {
        return _httpInput;
    }

    public HttpOutput getHttpOutput()
    {
        return _response.getHttpOutput();
    }

    public void errorClose()
    {
        // Make the response immutable and soft close the output.
    }

    public boolean isHead()
    {
        return HttpMethod.HEAD.is(getMethod());
    }

    @Override
    public Object getAttribute(String name)
    {
        // return hidden attributes for request logging
        // TODO does this actually work?   Does the request logger have the wrapped request?
        return switch (name)
        {
            case "o.e.j.s.s.ServletScopedRequest.request" -> _httpServletRequest;
            case "o.e.j.s.s.ServletScopedRequest.response" -> _response.getHttpServletResponse();
            case "o.e.j.s.s.ServletScopedRequest.servlet" -> _mappedServlet.getServletPathMapping(getPathInContext()).getServletName();
            case "o.e.j.s.s.ServletScopedRequest.url-pattern" -> _mappedServlet.getServletPathMapping(getPathInContext()).getPattern();
            default -> super.getAttribute(name);
        };
    }

    /**
     * @return The current {@link ContextHandler.Context context} used for this error handling for this request.  If the request is asynchronous,
     * then it is the context that called async. Otherwise it is the last non-null context passed to #setContext
     */
    public ServletContextHandler.Context getErrorContext()
    {
        // TODO: review.
        return _servletChannel.getContext();
    }

    ServletRequestState getServletRequestState()
    {
        return _servletChannel.getState();
    }

    ServletChannel getServletChannel()
    {
        return _servletChannel;
    }

    public HttpServletRequest getHttpServletRequest()
    {
        return _httpServletRequest;
    }

    public ServletApiRequest getServletApiRequest()
    {
        return _httpServletRequest;
    }

    public HttpServletResponse getHttpServletResponse()
    {
        return _response.getHttpServletResponse();
    }

    public ServletHandler.MappedServlet getMappedServlet()
    {
        return _mappedServlet;
    }

    public static ServletContextRequest getRequest(HttpServletRequest httpServletRequest)
    {
        while (httpServletRequest != null)
        {
            if (httpServletRequest instanceof ServletChannel)
                return ((ServletChannel)httpServletRequest).getRequest();
            if (httpServletRequest instanceof HttpServletRequestWrapper)
                httpServletRequest = (HttpServletRequest)((HttpServletRequestWrapper)httpServletRequest).getRequest();
            else
                break;
        }
        return null;
    }

    public String getServletName()
    {
        if (_scope != null)
            return _scope.getName();
        return null;
    }

    Runnable onContentAvailable()
    {
        // TODO not sure onReadReady is right method or at least could be renamed.
        return getState().onReadReady() ? this : null;
    }

    public void addEventListener(final EventListener listener)
    {
        if (listener instanceof ServletRequestAttributeListener)
            _requestAttributeListeners.add((ServletRequestAttributeListener)listener);
        if (listener instanceof AsyncListener)
            throw new IllegalArgumentException(listener.getClass().toString());
    }

    public void removeEventListener(final EventListener listener)
    {
        _requestAttributeListeners.remove(listener);
    }

    /**
     * Compare inputParameters to NO_PARAMS by Reference
     *
     * @param inputParameters The parameters to compare to NO_PARAMS
     * @return True if the inputParameters reference is equal to NO_PARAMS otherwise False
     */
    private static boolean isNoParams(MultiMap<String> inputParameters)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean isNoParams = (inputParameters == NO_PARAMS);
        return isNoParams;
    }

    public class ServletApiRequest implements HttpServletRequest
    {
        private AsyncContextState _async;
        private String _characterEncoding;
        private int _inputState = INPUT_NONE;
        private BufferedReader _reader;
        private String _readerEncoding;
        private String _contentType;
        private boolean _contentParamsExtracted;
        private MultiMap<String> _contentParameters;
        private MultiMap<String> _parameters;
        private MultiMap<String> _queryParameters;
        private SessionManager _sessionManager;
        private Session _session;
        private String _requestedSessionId;
        private boolean _requestedSessionIdFromCookie;
        
        public static Session getSession(HttpSession httpSession)
        {
            if (httpSession instanceof Session.APISession apiSession)
                return apiSession.getSession();
            return null;
        }

        protected void setSession(Session session)
        {
            _session = session;
        }

        public SessionManager getSessionManager()
        {
            return _sessionManager;
        }

        protected void setSessionManager(SessionManager sessionManager)
        {
            _sessionManager = sessionManager;
        }

        public ServletContextRequest getRequest()
        {
            return ServletContextRequest.this;
        }

        public HttpFields getFields()
        {
            return ServletContextRequest.this.getHeaders();
        }

        @Override
        public String getRequestId()
        {
            return ServletContextRequest.this.getConnectionMetaData().getId() + "#" + ServletContextRequest.this.getId();
        }

        @Override
        public String getProtocolRequestId()
        {
            return ServletContextRequest.this.getId();
        }

        @Override
        public ServletConnection getServletConnection()
        {
            // TODO cache the results
            final ConnectionMetaData connectionMetaData = ServletContextRequest.this.getConnectionMetaData();
            return new ServletConnection()
            {
                @Override
                public String getConnectionId()
                {
                    return connectionMetaData.getId();
                }

                @Override
                public String getProtocol()
                {
                    return connectionMetaData.getProtocol();
                }

                @Override
                public String getProtocolConnectionId()
                {
                    // TODO review
                    if (HttpVersion.HTTP_3.is(connectionMetaData.getProtocol()))
                        return connectionMetaData.getId();
                    return "";
                }

                @Override
                public boolean isSecure()
                {
                    return connectionMetaData.isSecure();
                }
            };
        }

        @Override
        public String getAuthType()
        {
            // TODO
            return null;
        }

        @Override
        public Cookie[] getCookies()
        {
            // TODO
            return new Cookie[0];
        }

        @Override
        public long getDateHeader(String name)
        {
            HttpFields fields = getFields();
            return fields == null ? -1 : fields.getDateField(name);
        }

        @Override
        public String getHeader(String name)
        {
            return getFields().get(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name)
        {
            return getFields().getValues(name);
        }

        @Override
        public Enumeration<String> getHeaderNames()
        {
            return getFields().getFieldNames();
        }

        @Override
        public int getIntHeader(String name)
        {
            HttpFields fields = getFields();
            return fields == null ? -1 : (int)fields.getLongField(name);
        }

        @Override
        public String getMethod()
        {
            return getRequest().getMethod();
        }

        @Override
        public String getPathInfo()
        {
            return ServletContextRequest.this._mappedServlet.getServletPathMapping(getRequest().getPathInContext()).getPathInfo();
        }

        @Override
        public String getPathTranslated()
        {
            String pathInfo = getPathInfo();
            if (pathInfo == null || getContext() == null)
                return null;
            return getContext().getServletContext().getRealPath(pathInfo);
        }

        @Override
        public String getContextPath()
        {
            return ServletContextRequest.this.getContext().getServletContextHandler().getRequestContextPath();
        }

        @Override
        public String getQueryString()
        {
            return ServletContextRequest.this.getHttpURI().getQuery();
        }

        @Override
        public String getRemoteUser()
        {
            Principal p = getUserPrincipal();
            if (p == null)
                return null;
            return p.getName();
        }

        @Override
        public boolean isUserInRole(String role)
        {
            // TODO
            return false;
        }

        @Override
        public Principal getUserPrincipal()
        {
            // TODO
            return null;
        }

        @Override
        public String getRequestedSessionId()
        {
            return _requestedSessionId;
        }
        
        protected void setRequestedSessionId(String requestedSessionId)
        {
            _requestedSessionId = requestedSessionId;
        }

        @Override
        public String getRequestURI()
        {
            HttpURI uri = ServletContextRequest.this.getHttpURI();
            return uri == null ? null : uri.getPath();
        }

        @Override
        public StringBuffer getRequestURL()
        {
            return new StringBuffer(ServletContextRequest.this.getHttpURI().asString());
        }

        @Override
        public String getServletPath()
        {
            return ServletContextRequest.this._mappedServlet.getServletPathMapping(_pathInContext).getServletPath();
        }

        @Override
        public HttpSession getSession(boolean create)
        {
            if (_session != null)
            {
                if (!_session.isValid())
                    _session = null;
                else
                    return _session.getAPISession();
            }

            if (!create)
                return null;

            if (getResponse().isCommitted())
                throw new IllegalStateException("Response is committed");

            if (_sessionManager == null)
                throw new IllegalStateException("No SessionManager");

            //TODO is this getBaseRequest or getRequest???
            _session = _sessionManager.newSession(ServletContextRequest.getBaseRequest(this), getRequestedSessionId());
            if (_session == null)
                throw new IllegalStateException("Create session failed");

            org.eclipse.jetty.http.HttpCookie cookie = _sessionManager.getSessionCookie(_session, getContextPath(), isSecure());

            if (cookie != null)
                Response.replaceCookie(ServletContextRequest.getBaseRequest(_httpServletRequest).getResponse(), cookie);

            return _session.getAPISession();
        }

        @Override
        public HttpSession getSession()
        {
            return getSession(true);
        }

        @Override
        public String changeSessionId()
        {
            HttpSession httpSession = getSession(false);
            if (httpSession == null)
                throw new IllegalStateException("No session");

            Session session = SessionHandler.ServletAPISession.getSession(httpSession);
            if (session == null)
                throw new IllegalStateException("!org.eclipse.jetty.session.Session");
            
            if (getSessionManager() == null)
                throw new IllegalStateException("No SessionManager.");

            session.renewId(ServletContextRequest.getBaseRequest(this));
            
            if (getRemoteUser() != null)
                session.setAttribute(Session.SESSION_CREATED_SECURE, Boolean.TRUE);
            
            if (session.isIdChanged() && getSessionManager().isUsingCookies())
                Response.replaceCookie(ServletContextRequest.getBaseRequest(_httpServletRequest).getResponse(), 
                    getSessionManager().getSessionCookie(session, getContextPath(), isSecure()));

            return session.getId();
        }

        @Override
        public boolean isRequestedSessionIdValid()
        {
            if (getRequestedSessionId() == null || _session == null)
                return false;
            //check requestedId (which may have worker suffix) against the actual session id
            return (getSessionManager().getSessionIdManager().getId(getRequestedSessionId()).equals(_session.getId()));
        }

        @Override
        public boolean isRequestedSessionIdFromCookie()
        {
            return _requestedSessionIdFromCookie;
        }
        
        protected void setRequestedSessionIdFromCookie(boolean requestedSessionIdFromCookie)
        {
            _requestedSessionIdFromCookie = requestedSessionIdFromCookie;
        }

        @Override
        public boolean isRequestedSessionIdFromURL()
        {
            return getRequestedSessionId() != null && !isRequestedSessionIdFromCookie(); 
        }

        @Override
        public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
        {
            // TODO
            return false;
        }

        @Override
        public void login(String username, String password) throws ServletException
        {
            // TODO
        }

        @Override
        public void logout() throws ServletException
        {
            // TODO
        }

        @Override
        public Collection<Part> getParts() throws IOException, ServletException
        {
            // TODO
            return null;
        }

        @Override
        public Part getPart(String name) throws IOException, ServletException
        {
            // TODO
            return null;
        }

        @Override
        public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException
        {
            // TODO
            return null;
        }

        @Override
        public Object getAttribute(String name)
        {
            if (_async != null)
            {
                // This switch works by allowing the attribute to get underneath any dispatch wrapper.
                switch (name)
                {
                    case AsyncContext.ASYNC_REQUEST_URI:
                        return getRequestURI();
                    case AsyncContext.ASYNC_CONTEXT_PATH:
                        return getContextPath();
                    case AsyncContext.ASYNC_SERVLET_PATH:
                        return getServletPath();
                    case AsyncContext.ASYNC_PATH_INFO:
                        return getPathInfo();
                    case AsyncContext.ASYNC_QUERY_STRING:
                        return getQueryString();
                    case AsyncContext.ASYNC_MAPPING:
                        return getHttpServletMapping();
                    default:
                        break;
                }
            }

            return ServletContextRequest.this.getAttribute(name);
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            Set<String> set = ServletContextRequest.this.getAttributeNameSet();
            if (_async != null)
            {
                set = new HashSet<>(set);
                set.add(AsyncContext.ASYNC_REQUEST_URI);
                set.add(AsyncContext.ASYNC_CONTEXT_PATH);
                set.add(AsyncContext.ASYNC_SERVLET_PATH);
                set.add(AsyncContext.ASYNC_PATH_INFO);
                set.add(AsyncContext.ASYNC_QUERY_STRING);
                set.add(AsyncContext.ASYNC_MAPPING);
            }

            return Collections.enumeration(set);
        }

        @Override
        public String getCharacterEncoding()
        {
            if (_characterEncoding == null)
            {
                if (getContext() != null)
                    _characterEncoding = getContext().getServletContext().getRequestCharacterEncoding();

                if (_characterEncoding == null)
                {
                    String contentType = getContentType();
                    if (contentType != null)
                    {
                        MimeTypes.Type mime = MimeTypes.CACHE.get(contentType);
                        String charset = (mime == null || mime.getCharset() == null) ? MimeTypes.getCharsetFromContentType(contentType) : mime.getCharset().toString();
                        if (charset != null)
                            _characterEncoding = charset;
                    }
                }
            }
            return _characterEncoding;
        }

        @Override
        public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException
        {
            if (_inputState != INPUT_NONE)
                return;

            _characterEncoding = encoding;

            // check encoding is supported
            if (!StringUtil.isUTF8(encoding))
            {
                try
                {
                    Charset.forName(encoding);
                }
                catch (UnsupportedCharsetException e)
                {
                    throw new UnsupportedEncodingException(e.getMessage());
                }
            }
        }

        @Override
        public int getContentLength()
        {
            long contentLength = getContentLengthLong();
            if (contentLength > Integer.MAX_VALUE)
                // Per ServletRequest#getContentLength() javadoc this must return -1 for values exceeding Integer.MAX_VALUE
                return -1;
            return (int)contentLength;
        }

        @Override
        public long getContentLengthLong()
        {
            // Even thought the metadata might know the real content length,
            // we always look at the headers because the length may be changed by interceptors.
            if (getFields() == null)
                return -1;

            return getFields().getLongField(HttpHeader.CONTENT_LENGTH);
        }

        @Override
        public String getContentType()
        {
            if (_contentType == null)
                _contentType = getFields().get(HttpHeader.CONTENT_TYPE);
            return _contentType;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException
        {
            if (_inputState != INPUT_NONE && _inputState != INPUT_STREAM)
                throw new IllegalStateException("READER");
            _inputState = INPUT_STREAM;

            if (_servletChannel.isExpecting100Continue())
                _servletChannel.continue100(_httpInput.available());

            return _httpInput;
        }

        @Override
        public String getParameter(String name)
        {
            return getParameters().getValue(name, 0);
        }

        @Override
        public Enumeration<String> getParameterNames()
        {
            return Collections.enumeration(getParameters().keySet());
        }

        @Override
        public String[] getParameterValues(String name)
        {
            List<String> vals = getParameters().getValues(name);
            if (vals == null)
                return null;
            return vals.toArray(new String[0]);
        }

        @Override
        public Map<String, String[]> getParameterMap()
        {
            return Collections.unmodifiableMap(getParameters().toStringArrayMap());
        }

        private MultiMap<String> getParameters()
        {
            if (!_contentParamsExtracted)
            {
                // content parameters need boolean protection as they can only be read
                // once, but may be reset to null by a reset
                _contentParamsExtracted = true;

                // Extract content parameters; these cannot be replaced by a forward()
                // once extracted and may have already been extracted by getParts() or
                // by a processing happening after a form-based authentication.
                if (_contentParameters == null)
                {
                    try
                    {
                        extractContentParameters();
                    }
                    catch (IllegalStateException | IllegalArgumentException e)
                    {
                        LOG.warn(e.toString());
                        throw new BadMessageException("Unable to parse form content", e);
                    }
                }
            }

            // Extract query string parameters; these may be replaced by a forward()
            // and may have already been extracted by mergeQueryParameters().
            if (_queryParameters == null)
                extractQueryParameters();

            // Do parameters need to be combined?
            if (isNoParams(_queryParameters) || _queryParameters.size() == 0)
                _parameters = _contentParameters;
            else if (isNoParams(_contentParameters) || _contentParameters.size() == 0)
                _parameters = _queryParameters;
            else if (_parameters == null)
            {
                _parameters = new MultiMap<>();
                _parameters.addAllValues(_queryParameters);
                _parameters.addAllValues(_contentParameters);
            }

            // protect against calls to recycled requests (which is illegal, but
            // this gives better failures
            MultiMap<String> parameters = _parameters;
            return parameters == null ? NO_PARAMS : parameters;
        }

        private void extractContentParameters()
        {
            String contentType = getContentType();
            if (contentType == null || contentType.isEmpty())
                _contentParameters = NO_PARAMS;
            else
            {
                _contentParameters = new MultiMap<>();
                // TODO
            }
        }

        private void extractQueryParameters()
        {
            HttpURI httpURI = ServletContextRequest.this.getHttpURI();
            if (httpURI == null || StringUtil.isEmpty(httpURI.getQuery()))
                _queryParameters = NO_PARAMS;
            else
            {
                try
                {
                    _queryParameters = Request.extractQueryParameters(ServletContextRequest.this);
                }
                catch (IllegalStateException | IllegalArgumentException e)
                {
                    _queryParameters = BAD_PARAMS;
                    throw new BadMessageException("Unable to parse URI query", e);
                }
            }
        }

        @Override
        public String getProtocol()
        {
            return ServletContextRequest.this.getConnectionMetaData().getProtocol();
        }

        @Override
        public String getScheme()
        {
            return ServletContextRequest.this.getHttpURI().getScheme();
        }

        @Override
        public String getServerName()
        {
            HttpURI uri = ServletContextRequest.this.getHttpURI();
            if ((uri != null) && StringUtil.isNotBlank(uri.getAuthority()))
                return formatAddrOrHost(uri.getHost());
            else
                return findServerName();
        }

        private String formatAddrOrHost(String name)
        {
            return _servletChannel == null ? HostPort.normalizeHost(name) : _servletChannel.formatAddrOrHost(name);
        }

        private String findServerName()
        {
            if (_servletChannel != null)
            {
                HostPort serverAuthority = _servletChannel.getServerAuthority();
                if (serverAuthority != null)
                    return formatAddrOrHost(serverAuthority.getHost());
            }

            // Return host from connection
            String name = getLocalName();
            if (name != null)
                return formatAddrOrHost(name);

            return ""; // not allowed to be null
        }

        @Override
        public int getServerPort()
        {
            int port = -1;

            HttpURI uri = ServletContextRequest.this.getHttpURI();
            if ((uri != null) && StringUtil.isNotBlank(uri.getAuthority()))
                port = uri.getPort();
            else
                port = findServerPort();

            // If no port specified, return the default port for the scheme
            if (port <= 0)
                return HttpScheme.getDefaultPort(getScheme());

            // return a specific port
            return port;
        }

        private int findServerPort()
        {
            if (_servletChannel != null)
            {
                HostPort serverAuthority = _servletChannel.getServerAuthority();
                if (serverAuthority != null)
                    return serverAuthority.getPort();
            }

            // Return host from connection
            return getLocalPort();
        }

        @Override
        public BufferedReader getReader() throws IOException
        {
            if (_inputState != INPUT_NONE && _inputState != INPUT_READER)
                throw new IllegalStateException("STREAMED");

            if (_inputState == INPUT_READER)
                return _reader;

            String encoding = getCharacterEncoding();
            if (encoding == null)
                encoding = StringUtil.__ISO_8859_1;

            if (_reader == null || !encoding.equalsIgnoreCase(_readerEncoding))
            {
                final ServletInputStream in = getInputStream();
                _readerEncoding = encoding;
                _reader = new BufferedReader(new InputStreamReader(in, encoding))
                {
                    @Override
                    public void close() throws IOException
                    {
                        in.close();
                    }
                };
            }
            _inputState = INPUT_READER;
            return _reader;
        }

        @Override
        public String getRemoteAddr()
        {
            return Request.getRemoteAddr(ServletContextRequest.this);
        }

        @Override
        public String getRemoteHost()
        {
            // TODO: review.
            return Request.getRemoteAddr(ServletContextRequest.this);
        }

        @Override
        public void setAttribute(String name, Object o)
        {
            ServletContextRequest.this.setAttribute(name, o);
        }

        @Override
        public void removeAttribute(String name)
        {
            ServletContextRequest.this.removeAttribute(name);
        }

        @Override
        public Locale getLocale()
        {
            HttpFields fields = getFields();
            if (fields == null)
                return Locale.getDefault();

            List<String> acceptable = fields.getQualityCSV(HttpHeader.ACCEPT_LANGUAGE);

            // handle no locale
            if (acceptable.isEmpty())
                return Locale.getDefault();

            String language = acceptable.get(0);
            language = HttpField.stripParameters(language);
            String country = "";
            int dash = language.indexOf('-');
            if (dash > -1)
            {
                country = language.substring(dash + 1).trim();
                language = language.substring(0, dash).trim();
            }
            return new Locale(language, country);
        }

        @Override
        public Enumeration<Locale> getLocales()
        {
            HttpFields fields = getFields();
            if (fields == null)
                return Collections.enumeration(__defaultLocale);

            List<String> acceptable = fields.getQualityCSV(HttpHeader.ACCEPT_LANGUAGE);

            // handle no locale
            if (acceptable.isEmpty())
                return Collections.enumeration(__defaultLocale);

            List<Locale> locales = acceptable.stream().map(language ->
            {
                language = HttpField.stripParameters(language);
                String country = "";
                int dash = language.indexOf('-');
                if (dash > -1)
                {
                    country = language.substring(dash + 1).trim();
                    language = language.substring(0, dash).trim();
                }
                return new Locale(language, country);
            }).collect(Collectors.toList());

            return Collections.enumeration(locales);
        }

        @Override
        public boolean isSecure()
        {
            return ServletContextRequest.this.getConnectionMetaData().isSecure();
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String path)
        {
            ServletContextHandler.Context context = ServletContextRequest.this.getContext();
            if (path == null || context == null)
                return null;

            // handle relative path
            if (!path.startsWith("/"))
            {
                String relTo = _pathInContext;
                int slash = relTo.lastIndexOf("/");
                if (slash > 1)
                    relTo = relTo.substring(0, slash + 1);
                else
                    relTo = "/";
                path = URIUtil.addPaths(relTo, path);
            }

            return context.getServletContext().getRequestDispatcher(path);
        }

        @Override
        public int getRemotePort()
        {
            return Request.getRemotePort(ServletContextRequest.this);
        }

        @Override
        public String getLocalName()
        {
            if (_servletChannel != null)
            {
                String localName = _servletChannel.getLocalName();
                return formatAddrOrHost(localName);
            }

            return ""; // not allowed to be null
        }

        @Override
        public String getLocalAddr()
        {
            return Request.getLocalAddr(ServletContextRequest.this);
        }

        @Override
        public int getLocalPort()
        {
            return Request.getLocalPort(ServletContextRequest.this);
        }

        @Override
        public ServletContext getServletContext()
        {
            return _servletChannel.getServletContext();
        }

        @Override
        public AsyncContext startAsync() throws IllegalStateException
        {
            ServletRequestState state = getState();
            if (_async == null)
                _async = new AsyncContextState(state);
            // TODO adapt to new context and base Request
            AsyncContextEvent event = new AsyncContextEvent(null, _async, state, this, _response.getHttpServletResponse());
            state.startAsync(event);
            return _async;
        }

        @Override
        public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
        {
            ServletRequestState state = getState();
            if (_async == null)
                _async = new AsyncContextState(state);
            // TODO adapt to new context and base Request
            AsyncContextEvent event = new AsyncContextEvent(null, _async, state, servletRequest, servletResponse);
            state.startAsync(event);
            return _async;
        }

        @Override
        public HttpServletMapping getHttpServletMapping()
        {
            return _mappedServlet.getServletPathMapping(_pathInContext);
        }

        @Override
        public boolean isAsyncStarted()
        {
            return getState().isAsyncStarted();
        }

        @Override
        public boolean isAsyncSupported()
        {
            return true;
        }

        @Override
        public AsyncContext getAsyncContext()
        {
            ServletRequestState state = _servletChannel.getState();
            if (_async == null || !state.isAsyncStarted())
                throw new IllegalStateException(state.getStatusString());

            return _async;
        }

        @Override
        public DispatcherType getDispatcherType()
        {
            return DispatcherType.REQUEST;
        }
    }
}