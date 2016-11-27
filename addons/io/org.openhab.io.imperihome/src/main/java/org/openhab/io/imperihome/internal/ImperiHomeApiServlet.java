/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.io.imperihome.internal;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.smarthome.core.events.EventPublisher;
import org.eclipse.smarthome.core.items.ItemRegistry;
import org.eclipse.smarthome.core.persistence.PersistenceServiceRegistry;
import org.openhab.io.imperihome.internal.action.ActionRegistry;
import org.openhab.io.imperihome.internal.handler.DeviceActionHandler;
import org.openhab.io.imperihome.internal.handler.DeviceHistoryHandler;
import org.openhab.io.imperihome.internal.handler.DevicesListHandler;
import org.openhab.io.imperihome.internal.handler.RoomListHandler;
import org.openhab.io.imperihome.internal.handler.SystemHandler;
import org.openhab.io.imperihome.internal.io.DeviceParametersSerializer;
import org.openhab.io.imperihome.internal.io.DeviceTypeSerializer;
import org.openhab.io.imperihome.internal.io.ParamTypeSerializer;
import org.openhab.io.imperihome.internal.model.device.DeviceType;
import org.openhab.io.imperihome.internal.model.param.DeviceParameters;
import org.openhab.io.imperihome.internal.model.param.ParamType;
import org.openhab.io.imperihome.internal.processor.DeviceRegistry;
import org.openhab.io.imperihome.internal.processor.ItemProcessor;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Main OSGi service and HTTP servlet for ImperiHome integration.
 *
 * @author Pepijn de Geus - Initial contribution
 */
public class ImperiHomeApiServlet extends HttpServlet {

    private static final long serialVersionUID = -1966364789075448441L;

    private static final String PATH = "/imperihome/iss";

    private static final String APPLICATION_JSON = "application/json";
    private static final String CHARSET = "utf-8";

    private static final Pattern URL_PATTERN_SYSTEM = Pattern.compile(PATH + "/system$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PATTERN_ROOMS = Pattern.compile(PATH + "/rooms$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PATTERN_DEVICES = Pattern.compile(PATH + "/devices$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PATTERN_DEVICE_ACTION = Pattern
            .compile(PATH + "/devices/(.+?)/action/(.+?)/(.*?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PATTERN_DEVICE_HISTORY = Pattern
            .compile(PATH + "/devices/(.+?)/(.+?)/histo/(.+?)/(.+?)$", Pattern.CASE_INSENSITIVE);

    private final Logger logger = LoggerFactory.getLogger(ImperiHomeApiServlet.class);

    private final Gson gson;

    private HttpService httpService;
    private ItemRegistry itemRegistry;
    private PersistenceServiceRegistry persistenceServiceRegistry;
    private EventPublisher eventPublisher;

    private ItemProcessor itemProcessor;
    private DevicesListHandler devicesListHandler;
    private RoomListHandler roomListHandler;
    private SystemHandler systemHandler;
    private DeviceRegistry deviceRegistry;
    private DeviceActionHandler deviceActionHandler;
    private DeviceHistoryHandler deviceHistoryHandler;
    private ActionRegistry actionRegistry;

    /**
     * Default constructor.
     */
    public ImperiHomeApiServlet() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(DeviceType.class, new DeviceTypeSerializer());
        gsonBuilder.registerTypeAdapter(ParamType.class, new ParamTypeSerializer());
        gsonBuilder.registerTypeAdapter(DeviceParameters.class, new DeviceParametersSerializer());
        gson = gsonBuilder.create();
    }

    /**
     * OSGi activation callback.
     *
     * @param config Service config.
     */
    protected void activate(Map<String, Object> config) {
        systemHandler = new SystemHandler();
        deviceRegistry = new DeviceRegistry();
        actionRegistry = new ActionRegistry(eventPublisher);
        itemProcessor = new ItemProcessor(itemRegistry, deviceRegistry, actionRegistry);
        roomListHandler = new RoomListHandler(deviceRegistry);
        devicesListHandler = new DevicesListHandler(deviceRegistry);
        deviceActionHandler = new DeviceActionHandler(deviceRegistry);
        deviceHistoryHandler = new DeviceHistoryHandler(deviceRegistry, persistenceServiceRegistry);

        try {
            Dictionary<String, String> servletParams = new Hashtable<String, String>();
            httpService.registerServlet(PATH, this, servletParams, httpService.createDefaultHttpContext());
            logger.info("Started ImperiHome integration service at " + PATH);
        } catch (Exception e) {
            logger.error("Could not start ImperiHome integration service: {}", e.getMessage(), e);
        }
    }

    /**
     * OSGi deactivation callback.
     *
     * @param componentContext Context.
     */
    protected void deactivate(ComponentContext componentContext) {
        try {
            httpService.unregister(PATH);
        } catch (IllegalArgumentException ignored) {
        }

        itemProcessor.destroy();

        systemHandler = null;
        deviceRegistry = null;
        itemProcessor = null;
        roomListHandler = null;
        devicesListHandler = null;
        deviceActionHandler = null;
        deviceHistoryHandler = null;

        logger.info("ImperiHome integration service stopped");
    }

    protected void setItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    protected void unsetItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = null;
    }

    protected void setEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    protected void unsetEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = null;
    }

    protected void setHttpService(HttpService httpService) {
        this.httpService = httpService;
    }

    protected void unsetHttpService(HttpService httpService) {
        this.httpService = null;
    }

    protected void setPersistenceServiceRegistry(PersistenceServiceRegistry persistenceServiceRegistry) {
        this.persistenceServiceRegistry = persistenceServiceRegistry;
    }

    protected void unsetPersistenceServiceRegistry(PersistenceServiceRegistry persistenceServiceRegistry) {
        this.persistenceServiceRegistry = null;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.getRequestURI();
        logger.debug("{}: {} {}", req.getRemoteAddr(), req.getMethod(), path);
        setHeaders(resp);

        Object response = null;

        Matcher actionMatcher = URL_PATTERN_DEVICE_ACTION.matcher(path);
        Matcher historyMatcher = URL_PATTERN_DEVICE_HISTORY.matcher(path);

        if (URL_PATTERN_ROOMS.matcher(path).matches()) {
            response = roomListHandler.handle(req);
        } else if (URL_PATTERN_DEVICES.matcher(path).matches()) {
            response = devicesListHandler.handle(req);
        } else if (actionMatcher.matches()) {
            deviceActionHandler.handle(req, actionMatcher);
        } else if (historyMatcher.matches()) {
            response = deviceHistoryHandler.handle(req, historyMatcher);
        } else if (URL_PATTERN_SYSTEM.matcher(path).matches()) {
            response = systemHandler.handle(req);
        } else {
            logger.warn("Unrecognized request: {}", path);
        }

        resp.getWriter().write(gson.toJson(response));
    }

    private void setHeaders(HttpServletResponse response) {
        response.setCharacterEncoding(CHARSET);
        response.setContentType(APPLICATION_JSON);
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS, DELETE, PUT");
        response.setHeader("Access-Control-Max-Age", "3600");
        response.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
    }

}