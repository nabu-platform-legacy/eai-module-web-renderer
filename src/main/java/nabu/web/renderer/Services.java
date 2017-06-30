package nabu.web.renderer;

import java.net.URI;

import javax.jws.WebParam;
import javax.jws.WebResult;
import javax.jws.WebService;
import javax.validation.constraints.NotNull;

import be.nabu.eai.module.http.client.HTTPClientArtifact;
import be.nabu.eai.module.http.server.renderer.Renderer;
import be.nabu.eai.module.web.application.WebApplication;
import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.HTTPClient;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.services.api.ExecutionContext;
import be.nabu.utils.mime.api.ModifiablePart;
import be.nabu.utils.mime.api.Part;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

@WebService
public class Services {
	
	private ExecutionContext executionContext;
	
	@WebResult(name = "response")
	public HTTPResponse execute(@NotNull @WebParam(name = "webApplicationId") String webApplicationId, @WebParam(name = "method") String method, @NotNull @WebParam(name = "url") URI url, @WebParam(name = "part") Part part, @WebParam(name = "token") Token token, @WebParam(name = "httpClientId") String httpClientId) {
		WebApplication application = executionContext.getServiceContext().getResolver(WebApplication.class).resolve(webApplicationId);
		if (application == null) {
			throw new IllegalArgumentException("Unknown web application: " + webApplicationId);
		}
		String host = url.getHost();
		if (host == null) {
			host = application.getConfig().getVirtualHost().getConfig().getHost();
		}
		HTTPClientArtifact clientArtifact = httpClientId == null ? null : executionContext.getServiceContext().getResolver(HTTPClientArtifact.class).resolve(httpClientId);
		HTTPClient client;
		try {
			client = nabu.protocols.http.client.Services.newClient(clientArtifact);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		return Renderer.execute(application, 
			new DefaultHTTPRequest(method == null ? "GET" : method.toUpperCase(), url.toString(), part != null ? (ModifiablePart) part : new PlainMimeEmptyPart(null, 
				new MimeHeader("Host", host),
				new MimeHeader("Content-Length", "0"))), 
			token,
			client);
	}
	
}
