/*
* Copyright (C) 2017 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package nabu.web.renderer;

import java.net.URI;
import java.util.Date;

import javax.jws.WebParam;
import javax.jws.WebResult;
import javax.jws.WebService;
import javax.validation.constraints.NotNull;

import be.nabu.eai.module.http.client.HTTPClientArtifact;
import be.nabu.eai.module.http.server.renderer.Renderer;
import be.nabu.eai.module.http.server.renderer.RendererArtifact;
import be.nabu.eai.module.web.application.WebApplication;
import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.HTTPClient;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.services.ServiceRuntime;
import be.nabu.libs.services.api.ExecutionContext;
import be.nabu.utils.mime.api.ModifiablePart;
import be.nabu.utils.mime.api.Part;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

@WebService
public class Services {
	
	private ExecutionContext executionContext;
	
	@WebResult(name = "response")
	public HTTPResponse execute(@NotNull @WebParam(name = "webApplicationId") String webApplicationId, @WebParam(name = "method") String method, @NotNull @WebParam(name = "url") URI url, @WebParam(name = "part") Part part, @WebParam(name = "token") Token token, @WebParam(name = "httpClientId") String httpClientId, @WebParam(name = "javascript") String javascript, @WebParam(name = "disableSsrBypass") Boolean disableSsrBypass, @WebParam(name = "disableCss") Boolean disableCss, @WebParam(name = "host") String host) {
		WebApplication application = executionContext.getServiceContext().getResolver(WebApplication.class).resolve(webApplicationId);
		if (application == null) {
			throw new IllegalArgumentException("Unknown web application: " + webApplicationId);
		}
		// if you didn't pass a host separately, get it from url
		if (host == null) {
			host = url.getHost();
		}
		// else get from virtual host
		if (host == null) {
			host = application.getConfig().getVirtualHost().getConfig().getHost();
		}
		
		if (part == null) {
			part = new PlainMimeEmptyPart(null, 
				new MimeHeader("Host", host),
				new MimeHeader("Content-Length", "0"));
		}
		if (part instanceof ModifiablePart && MimeUtils.getHeader("Host", part.getHeaders()) == null) {
			((ModifiablePart) part).setHeader(new MimeHeader("Host", host));
		}
		
		HTTPClientArtifact clientArtifact = httpClientId == null ? null : executionContext.getServiceContext().getResolver(HTTPClientArtifact.class).resolve(httpClientId);
		HTTPClient client;
		try {
			client = nabu.protocols.http.client.Services.newClient(clientArtifact);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		HTTPResponse response;
		// we need to unregister the current runtime that leads to this service invocation
		// otherwise, _all_ services while rendering the application (e.g. language resolving etc) will build on this service context
		// this is really bad for things like tracing mode, which recursively checks all parents, so if you trace the render itself, you actually trace all services it indirectly calls
		ServiceRuntime runtime = ServiceRuntime.getRuntime();
		try {
			if (runtime != null) {
				runtime.unregisterInThread();
			}
			response = Renderer.execute(application, 
				new DefaultHTTPRequest(method == null ? "GET" : method.toUpperCase(), url.toString(), (ModifiablePart) part), 
				token,
				client,
				javascript,
				disableSsrBypass == null || !disableSsrBypass,
				disableCss != null && disableCss ? false : true
			);
		}
		finally {
			if (runtime != null) {
				runtime.registerInThread(false);
			}
		}
		return response;
	}
	
	public void cache(@NotNull @WebParam(name = "webApplicationId") String webApplicationId, @NotNull @WebParam(name = "rendererId") String rendererId, @NotNull @WebParam(name = "url") URI uri, @WebParam(name = "lastModified") Date lastModified) {
		WebApplication application = executionContext.getServiceContext().getResolver(WebApplication.class).resolve(webApplicationId);
		RendererArtifact renderer = executionContext.getServiceContext().getResolver(RendererArtifact.class).resolve(rendererId);
		ServiceRuntime runtime = ServiceRuntime.getRuntime();
		try {
			if (runtime != null) {
				runtime.unregisterInThread();
			}
			renderer.cache(application, uri, lastModified);
		}
		finally {
			if (runtime != null) {
				runtime.registerInThread(false);
			}
		}
	}
}
