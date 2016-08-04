/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.wicket.portlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.GenericPortlet;
import javax.portlet.MimeResponse;
import javax.portlet.PortletConfig;
import javax.portlet.PortletException;
import javax.portlet.PortletMode;
import javax.portlet.PortletRequest;
import javax.portlet.PortletRequestDispatcher;
import javax.portlet.PortletResponse;
import javax.portlet.RenderRequest;
import javax.portlet.RenderResponse;
import javax.portlet.ResourceRequest;
import javax.portlet.ResourceResponse;
import javax.servlet.RequestDispatcher;

import org.apache.wicket.protocol.http.WicketFilter;
import org.apache.wicket.util.file.File;
import org.apache.wicket.util.string.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter between the Portlet world requests and the internal Wicket engine.
 * I.e. simulates the web/servlet environment for Wicket, while it's actually
 * running as a Portlet.
 * 
 * <p>
 * It receives a portlet request and dispatches to a a Wicket filter; wrapping
 * the servlet context, request and response objects; intercepts response
 * writing (especially urls and redirects) and rewrites and adapts the output to
 * accommodate the portlet requirements.
 * 
 * <p>
 * The WicketPortlet is configured (using an initParameter) against a specific
 * filter path, e.g. Wicket WebApplication. The WicketPortlet maintains a
 * parameter for the current Wicket page URL being requested as a URL parameter,
 * based against the filter path (e.g. fully qualified to the context path).
 * When a request (action, render or direct resource/ajax call) is received by
 * the WicketPortlet, it dispatches it to Wicket core as a filter request using
 * the provided Wicket page URL parameter.
 * 
 * @see WicketPortlet#WICKET_URL_PORTLET_PARAMETER
 * @see WicketFilter
 * 
 * @author Ate Douma
 * @author <a href="http://sebthom.de/">Sebastian Thomschke</a>
 * @author Peter Pastrnak
 * @author Konstantinos Karavitis
 */
public class WicketPortlet extends GenericPortlet {
	private static final Pattern PROTECTED_RESOURCES = Pattern.compile("\\A\\s*[/\\\\]*\\s*(WEB|META)[-]INF(.*)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

	private static final Pattern ABSOLUTE_URI_PATTERN = Pattern.compile("([a-z][a-z0-9]*://|/).*");

	public static enum PageType {
		ACTION("actionPage"), //
		CUSTOM("customPage"), //
		EDIT("editPage"), //
		HELP("helpPage"), //
		VIEW("viewPage");

		public static PageType getByInitParameterName(final String initParameterName) {
			for (final PageType p : PageType.values())
				if (p.initParameterName.equals(initParameterName))
					return p;
			return null;
		}

		public final String initParameterName;

		PageType(final String initParameterName) {
			this.initParameterName = initParameterName;
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(WicketPortlet.class);

	/**
	 * FIXME javadoc
	 * 
	 * <p>
	 * The prefix for the parameter name for storing Wicket URLs.
	 * 
	 * <p>
	 * The actual Wicket URLs generated by Wicket are passed around in portal
	 * URLs, encoded by the portal (as a URL parameter of this name). The Wicket
	 * URL is later decoded on subsequent requests, from the portal URL, so that
	 * we know where to route the request, once it's passed out of the 'portal'
	 * realm and into the 'Wicket' realm.
	 * 
	 * <p>
	 * This is also used in generating links by {@link PortletRequestContext} in
	 * generating links, as the links have to be portal encoded links, but must
	 * also still contain the original wicket url for use by Wicket (e.g.
	 * {@link PortletRequestContext#encodeActionURL}).
	 * 
	 * <p>
	 * The default/buildin name of the parameter which stores the name of the
	 * wicket url is stored under {@link #WICKET_URL_PORTLET_PARAMETER}. It will
	 * be stored suffixed with the current portlet mode (e.g. view), so that
	 * render requests know what mode to render.
	 * 
	 * @see PortletRequestContext
	 */
	public static final String WICKET_URL_PORTLET_PARAMETER = "_wu";

	/**
	 * FIXME javadoc
	 */
	public static final String WICKET_FILTER_PATH_PARAM = "wicketFilterPath";
	/**
	 * FIXME javadoc
	 */
	public static final String RESPONSE_BUFFER_FOLDER_PARAM = "responseBufferFolder";
	/**
	 * FIXME javadoc
	 */
	public static final String CONFIG_PARAM_PREFIX = WicketPortlet.class.getName() + ".";
	/**
	 * Marker used as key for the ResponseState object stored as a request
	 * attribute.
	 */
	public static final String RESPONSE_STATE_ATTR = ResponseState.class.getName();
	/** FIXME javadoc */
	public static final String WICKET_PORTLET_PROPERTIES = WicketPortlet.class.getName().replace('.', '/') + ".properties";

	/** FIXME javadoc */
	private String wicketFilterPath;
	
	/** FIXME javadoc */
	private File responseBufferFolder;

	/**
	 * A collection of the default URL's for the different view modes of the
	 * portlet - e.g. VIEW, EDIT, HELP etc...
	 */
	private final HashMap<PageType, String> defaultPages = new HashMap<PageType, String>();

	protected String buildWicketFilterPath(String filterPath) {
		if (filterPath == null || filterPath.length() == 0)
			return "/";

		if (!filterPath.startsWith("/"))
			filterPath = "/" + filterPath;
		if (filterPath.endsWith("*"))
			filterPath = filterPath.substring(0, filterPath.length() - 1);
		if (!filterPath.endsWith("/"))
			filterPath += "/";

		return filterPath;
	}

	/**
	 * Delegates to
	 * {@link #processRequest(PortletRequest, PortletResponse, String, String)}.
	 * 
	 * @see #processRequest(PortletRequest, PortletResponse, String, String)
	 */
	protected void doCustom(final RenderRequest request, final RenderResponse response) throws PortletException, IOException {
		processRequest(request, response, PageType.CUSTOM);
	}

	/**
	 * Delegates to
	 * {@link #processRequest(PortletRequest, PortletResponse, String, String)}.
	 * 
	 * @see #processRequest(PortletRequest, PortletResponse, String, String)
	 */
	@Override
	protected void doEdit(final RenderRequest request, final RenderResponse response) throws PortletException, IOException {
		processRequest(request, response, PageType.EDIT);
	}

	/**
	 * Delegates to
	 * {@link #processRequest(PortletRequest, PortletResponse, String, String)}.
	 * 
	 * @see #processRequest(PortletRequest, PortletResponse, String, String)
	 */
	@Override
	protected void doHelp(final RenderRequest request, final RenderResponse response) throws PortletException, IOException {
		processRequest(request, response, PageType.HELP);
	}

	/**
	 * Delegates to
	 * {@link #processRequest(PortletRequest, PortletResponse, String, String)}.
	 * 
	 * @see #processRequest(PortletRequest, PortletResponse, String, String)
	 */
	@Override
	protected void doView(final RenderRequest request, final RenderResponse response) throws PortletException, IOException {
		processRequest(request, response, PageType.VIEW);
	}

	/**
	 * @param pageType
	 *            the mode of the portlet page, e.g. VIEW, EDIT etc...
	 * @return the default page name for the given pate type.
	 */
	protected String getDefaultPage(final PageType pageType) {
		return defaultPages.get(pageType);
	}

	/**
	 * Loads the Wicket Portlet properties file off the class path.
	 * 
	 * FIXME javadoc - check properties
	 * 
	 * @param properties
	 *            appends the portlet properties to
	 * @return Wicket portlet properties. Returns an empty or unchanged
	 *         properties object if Wicket Portlet properties could not be found
	 * @throws PortletException
	 *             if loading the properties fails
	 */
	protected Properties getWicketPortletProperties(Properties properties) throws PortletException {
		if (properties == null)
			properties = new Properties();
		final InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(WICKET_PORTLET_PROPERTIES);
		if (is != null)
			try {
				properties.load(is);
			}
			catch (final IOException e) {
				throw new PortletException("Failed to load WicketPortlet.properties from classpath", e);
			}
		return properties;
	}

	/**
	 * Retrieves the Wicket URL from the request object as a request parameter,
	 * or if none exists returns the default URL. The name of the request
	 * parameter is stored as a request attribute.
	 * 
	 * <p>
	 * This url is then used to pass on to the matching {@link WicketFilter} to
	 * process, by way of {@link RequestDispatcher} via the filters context
	 * path.
	 * 
	 * <p>
	 * A "parameter" is a form field name/value pair passed from the HTML side
	 * of the world. Its value is a String.
	 * 
	 * <p>
	 * An "attribute" is a Java object name/value pair passed only through the
	 * internal JavaServer processes. (I.e. it can come from a JSP or servlet
	 * but not an HTML page.) Its value is an Object.
	 * 
	 * @see PortletRequestContext#getLastEncodedPath()
	 * @param request
	 * @param pageType
	 * @param defaultPage
	 *            url of the default page
	 * @return the Wicket URL from within the specified request
	 */
	protected String getWicketURL(final PortletRequest request, final PageType pageType, final String defaultPage) {
		String wicketURL = null;
		if (request instanceof ActionRequest)
			// try to lookup the passed in wicket url parameter
			wicketURL = request.getParameter(WICKET_URL_PORTLET_PARAMETER);
		else if (request instanceof ResourceRequest)
			wicketURL = ((ResourceRequest) request).getResourceID();
		else {
			// try to lookup the passed in wicket url parameter, suffixed with
			// the portlet mode
			String redirectUrlKey = WICKET_URL_PORTLET_PARAMETER + request.getPortletMode().toString();
			String redirectUrl = request.getParameter(redirectUrlKey);
			// if the wicket url is not in request parameters try to lookup into the action scoped
			// attributes.
			wicketURL = redirectUrl == null ? (String)request.getAttribute(redirectUrlKey) : redirectUrl;
		}

		// if the wicketURL could not be retrieved, return the url for the
		// default page
		if (wicketURL == null)
			wicketURL = defaultPage;
		return wicketURL;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void init(final PortletConfig config) throws PortletException {
		// enable action-scoped request attributes support (see JSR286 specification PLT.10.4.4)
		config.getContainerRuntimeOptions().put("javax.portlet.actionScopedRequestAttributes",
			new String[] { "true", "numberOfCachedScopes", "10" });
		super.init(config);

		wicketFilterPath = buildWicketFilterPath(config.getInitParameter(WICKET_FILTER_PATH_PARAM));
		String responseBufferFolderPath = config.getInitParameter(RESPONSE_BUFFER_FOLDER_PARAM);
		if ((responseBufferFolderPath != null) && (!Strings.isEmpty(responseBufferFolderPath))) {
			responseBufferFolder = new File(responseBufferFolderPath);
		}

		for (final PageType p : PageType.values()) {
			defaultPages.put(p, config.getInitParameter(p.initParameterName));
		}

		validateDefaultPages(defaultPages);
	}

	/**
	 * Delegates to
	 * {@link #processRequest(PortletRequest, PortletResponse, String, String)}.
	 * 
	 * <p>
	 * Stores the {@link ActionResponse} so that
	 * {@link PortletEventService#broadcast} can send events using
	 * {@link ActionResponse#setEvent}
	 * 
	 * @see PortletEventService#broadcastToPortletListeners
	 * @see #processRequest(PortletRequest, PortletResponse, String, String)
	 */
	@Override
	public void processAction(final ActionRequest request, final ActionResponse response) throws PortletException, IOException {
		processRequest(request, response, PageType.ACTION);
	}

	/**
	 * Handles redirects set from processing the action. Checks the response
	 * state after the action has been processed by Wicket for the presence of a
	 * redirect URL, and if present, 'portletifies' the URL. If the URL is a
	 * redirect to within the scope of this portlet, leaves it to be handled in
	 * a subsequent render call, or if not, sends the redirect to the client.
	 * The recorded url is then used in by wicket in the subsequnt 'VIEW'
	 * requests by the portal, to render the correct Page.
	 * 
	 * @see IRequestCycleSettings#REDIRECT_TO_RENDER
	 * @param wicketURL
	 * @param request
	 * @param response
	 * @param responseState
	 * @throws PortletException
	 * @throws IOException
	 */
	protected void processActionResponseState(String wicketURL, final PortletRequest request, final ActionResponse response, final ResponseState responseState) throws PortletException, IOException {
		// write out Cookies to ActionResponse
		responseState.flushAndClose();
		String redirectLocationUrl = responseState.getRedirectLocation();
		if (LOG.isDebugEnabled())
			LOG.debug("redirectURL after include:" + redirectLocationUrl);
		if (redirectLocationUrl != null && !redirectLocationUrl.isEmpty()) {
			redirectLocationUrl = fixWicketUrl(wicketURL, redirectLocationUrl, request.getScheme());
			if (redirectLocationUrl.startsWith(wicketFilterPath)) {
				final String portletMode = request.getPortletMode().toString();
				final String redirectUrlKey = WICKET_URL_PORTLET_PARAMETER + portletMode;
				// put the redirect location into the "_wuview" action scoped request attribute
				request.setAttribute(redirectUrlKey, redirectLocationUrl);
			}
			else
				response.sendRedirect(redirectLocationUrl);
		}
	}

	/**
	 * Loops until wicket processing does not result in a redirect (redirects
	 * have to be caught, and fed back into Wicket as we only want the portlet
	 * redirected, not the entire page of course).
	 * 
	 * @param request
	 * @param response
	 * @param requestType
	 * @param wicketURL
	 * @param responseState
	 * @throws PortletException
	 * @throws IOException
	 */
	private void processMimeResponseRequest(String wicketURL, final PortletRequest request, final MimeResponse response, final ResponseState responseState) throws PortletException, IOException {
		PortletRequestDispatcher rd = null;
		String previousURL = null;
		// FIXME portal comment: explain while loop
		// keep looping until wicket processing does not result in a redirect
		// (redirects have to
		// be caught, and fed back into Wicket as we only want the portlet
		// redirected, not the
		// entire page of course.
		while (true) {
			rd = getPortletContext().getRequestDispatcher(wicketURL);
			if (rd != null) {
				// Need to use RequestDispatcher.include here otherwise
				// internally rewinding on a
				// redirect
				// won't be allowed (calling forward will close the response)
				rd.include(request, response);

				// process _other_ response states - check for redirects as a
				// result of wicket
				// processing the request

				String redirectLocation = responseState.getRedirectLocation();
				String ajaxRedirectLocation = responseState.getAjaxRedirectLocation();
				if (ajaxRedirectLocation != null) {
					// Ajax redirect
					ajaxRedirectLocation = fixWicketUrl(wicketURL, ajaxRedirectLocation, request.getScheme());
					responseState.clear();
					responseState.setDateHeader("Date", System.currentTimeMillis());
					responseState.setDateHeader("Expires", 0);
					responseState.setHeader("Pragma", "no-cache");
					responseState.setHeader("Cache-Control", "no-cache, no-store");
					//client side javascript needs the Ajax-Location header see wicket-ajax-jquery.js line 771
					responseState.setHeader("Ajax-Location", ajaxRedirectLocation);//
					responseState.setContentType("text/xml;charset=UTF-8");
					responseState.getWriter().write(
						"<ajax-response><redirect><![CDATA[" + ajaxRedirectLocation +
							"]]></redirect></ajax-response>");
					responseState.flushAndClose();
				}
				else if (redirectLocation != null) {
					// TODO: check if its redirect to wicket page (find _wu or
					// _wuPortletMode or resourceId parameter)

					redirectLocation = fixWicketUrl(wicketURL, redirectLocation, request.getScheme());

					final boolean validWicketUrl = redirectLocation.startsWith(wicketFilterPath);
					if (validWicketUrl) {
						if (previousURL == null || previousURL != redirectLocation) {
							previousURL = wicketURL;
							wicketURL = redirectLocation;
							((RenderResponse) response).reset();
							responseState.clear();
							continue;
						}
						else {
							// internal Wicket redirection loop: unsure yet what
							// to send out from
							// here
							// TODO: determine what kind of error (message or
							// page) should be
							// written out
							// for now: no output available/written :(
							responseState.clear();
							break;
						}
					}
					else {
						responseState.clear();
						if (responseState.isResourceResponse()) {
							// Formally, the Portlet 2.0 Spec doesn't support
							// directly redirecting
							// from serveResource. However, it is possible to
							// write response headers
							// to the ResourceResponse (using setProperty),
							// which means the
							// technical implementation of a response.redirect
							// call might be
							// "simulated" by writing out:

							// a) setting response.setStatus(SC_FOUND)
							// b) setting header "Location" to the
							// redirectLocation

							// Caveat 1:
							// ResourceResponse.setStatus isn't supported
							// either, but this can be
							// done by setting the header property
							// ResourceResponse.HTTP_STATUS_CODE

							// Caveat 2: Actual handling of Response headers as
							// set through
							// PortletResponse.setProperty is completely
							// optional by the Portlet
							// Spec so it really depends on the portlet
							// container implementation
							// (and environment, e.g. consider using WSRP
							// here...) if this will
							// work.

							// On Apache Pluto/Jetspeed-2, the above descibed
							// handling *will* be
							// implemented as expected!

							// HttpServletResponse.SC_FOUND == 302, defined by
							// Servlet API >= 2.4
							response.setProperty(ResourceResponse.HTTP_STATUS_CODE, Integer.toString(302));
							response.setProperty("Location", redirectLocation);
						}
						else {
							response.reset();
							response.setProperty("expiration-cache", "0");

							PrintWriter writer = response.getWriter();
							writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
							writer.append("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\" \"http://www.w3.org/TR/html4/loose.dtd\">");
							writer.append("<html><head><meta http-equiv=\"refresh\" content=\"0; url=").append(redirectLocation).append("\"/></head></html>");
							writer.close();
							break;
						}
					}
				}
				else {
					if (LOG.isDebugEnabled()) {
						LOG.debug("ajax redirect url after inclusion:" + redirectLocation);
					}
					// write response state out to the PortletResponse
					responseState.flushAndClose();
				}
			}
			break;
		}
	}

	/**
	 * Consumes and processes all portlet requests. All the doX methods delegate
	 * to this method, including processAction and serveResource.
	 * 
	 * @param request
	 * @param response
	 * @param requestType
	 * @param pageType
	 * @throws PortletException
	 * @throws IOException
	 */
	protected void processRequest(final PortletRequest request, final PortletResponse response, final PageType pageType) throws PortletException, IOException {
		String wicketURL = null;

		// get the actual wicketURL for this request, to be passed onto Wicket
		// core for processing
		wicketURL = getWicketURL(request, pageType, getDefaultPage(pageType));

		if (LOG.isDebugEnabled())
			LOG.debug("Portlet \"" + request.getAttribute(PortletRequest.LIFECYCLE_PHASE) + "\" for wicket url:" + wicketURL);

		// store the response state and request type in the request object, so
		// they can be looked up
		// from a different context
		final ResponseState responseState = new ResponseState(request, response, responseBufferFolder);
		request.setAttribute(RESPONSE_STATE_ATTR, responseState);

		// need to record the effective wicket url of the rendered result, so
		// that the subsequent
		// portlet 'view' requests can delegate to wicket to render the correct
		// location/wicket url.
		if (responseState.isActionResponse()) {
			// create the request dispatcher, to delegate the request to the
			// wicket filter
			final PortletRequestDispatcher rd = getPortletContext().getRequestDispatcher(wicketURL);

			if (rd != null) {
				// delegate to wicket filter - this is where the magic happens
				rd.include(request, response);
				// String newWicketURL = getWicketURL(request, pageType,
				// getDefaultPage(pageType));
				LOG.debug("wicket filter inclusion complete");
				processActionResponseState(wicketURL, request, (ActionResponse) response, responseState);
			}
			else {
				// FIXME - throw exception?
				// no-op for now
			}
		}
		else if (responseState.isMimeResponse())
			processMimeResponseRequest(wicketURL, request, (MimeResponse) response, responseState);
		else
			LOG.warn("Unsupported Portlet lifecycle: {}", request.getAttribute(PortletRequest.LIFECYCLE_PHASE));
		if (LOG.isDebugEnabled()) {
			wicketURL = getWicketURL(request, pageType, getDefaultPage(pageType));
			LOG.debug("end of request, wicket url: " + wicketURL);
		}
	}

	/**
	 * Delegates to
	 * {@link #processRequest(PortletRequest, PortletResponse, String, String)}.
	 * 
	 * @see #processRequest(PortletRequest, PortletResponse, String, String)
	 */
	@Override
	public void serveResource(final ResourceRequest request, final ResourceResponse response) throws PortletException, IOException {
		String resourceId = request.getResourceID();
		if (resourceId != null) {
			if (PROTECTED_RESOURCES.matcher(resourceId).matches()) {
				response.setProperty(ResourceResponse.HTTP_STATUS_CODE, "404");
			}
			processRequest(request, response, PageType.VIEW);
		}
	}

	/**
	 * FIXME javadoc
	 * 
	 * <p>
	 * Corrects the incoming URL if the old home page style, or if it's missing
	 * the filter path prefix.
	 * 
	 * @param url
	 *            the URL to fix
	 * @return the corrected URL
	 */
	protected String fixWicketUrl(final String url) {
		if (url == null)
			return wicketFilterPath;

		if (!url.startsWith(wicketFilterPath)) {
			if (url.startsWith("..?")) {
				return wicketFilterPath + url.substring(2);
			}
			if ((url + "/").equals(wicketFilterPath)) {
				// hack around "old" style wicket home url's without trailing
				// '/' which would lead
				// to a redirect to the real home path anyway
				return wicketFilterPath;
			}
		}
		return url;
	}

	/**
	 * FIXME javadoc
	 * 
	 * <p>
	 * Corrects the incoming URL if the old home page style, or if it's missing
	 * the filter path prefix.
	 * 
	 * @param requestUrl
	 *            the original request URL
	 * @param url
	 *            the URL to fix
	 * @return the corrected URL
	 */
	protected String fixWicketUrl(final String requestUrl, final String url, final String scheme) {
		if ((url != null) && (requestUrl != null) && (!ABSOLUTE_URI_PATTERN.matcher(url).matches())) {
			try {
				if (!requestUrl.startsWith("http")) {
					return new URL(new URL(scheme + ":" + requestUrl), url).toString().substring(scheme.length() + 1);
				}
				else {
					return new URL(new URL(requestUrl), url).getPath();
				}
			}
			catch (Exception e) {
			}
		}
		return fixWicketUrl(url);
	}

	/**
	 * FIXME javadoc
	 * 
	 * <p>
	 * Registers the default pages and their URLs for the different
	 * {@link PortletMode}s. Also corrects and slightly incorrect URLs (see
	 * {@link #fixWicketUrl(String)}).
	 * 
	 * <p>
	 * If no specific page was specified for a given portlet mode (VIEW, EDIT
	 * etc) then the page for that mode is set to be the same page as that of
	 * the VIEW mode.
	 * 
	 * @see PortletMode
	 * @see #fixWicketUrl(String)
	 * @param defaultPages
	 */
	protected void validateDefaultPages(final Map<PageType, String> defaultPages) {
		final String viewPage = fixWicketUrl(defaultPages.get(PageType.VIEW));
		defaultPages.put(PageType.VIEW, viewPage.startsWith(wicketFilterPath) ? viewPage : wicketFilterPath);

		String defaultPage = defaultPages.get(PageType.ACTION);
		if (defaultPage == null)
			defaultPages.put(PageType.ACTION, viewPage);
		else {
			defaultPage = fixWicketUrl(defaultPage);
			defaultPages.put(PageType.ACTION, defaultPage.startsWith(wicketFilterPath) ? defaultPage : viewPage);
		}

		defaultPage = defaultPages.get(PageType.CUSTOM);
		if (defaultPage == null)
			defaultPages.put(PageType.CUSTOM, viewPage);
		else {
			defaultPage = fixWicketUrl(defaultPage);
			defaultPages.put(PageType.CUSTOM, defaultPage.startsWith(wicketFilterPath) ? defaultPage : viewPage);
		}

		defaultPage = defaultPages.get(PageType.HELP);
		if (defaultPage == null)
			defaultPages.put(PageType.HELP, viewPage);
		else {
			defaultPage = fixWicketUrl(defaultPage);
			defaultPages.put(PageType.HELP, defaultPage.startsWith(wicketFilterPath) ? defaultPage : viewPage);
		}

		defaultPage = defaultPages.get(PageType.EDIT);
		if (defaultPage == null)
			defaultPages.put(PageType.EDIT, viewPage);
		else {
			defaultPage = fixWicketUrl(defaultPage);
			defaultPages.put(PageType.EDIT, defaultPage.startsWith(wicketFilterPath) ? defaultPage : viewPage);
		}
	}
}
