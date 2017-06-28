package be.nabu.eai.module.http.server.renderer;

import be.nabu.libs.events.api.EventDispatcher;

public interface DispatcherResolver {
	public EventDispatcher getDispatcher(String id);
}
