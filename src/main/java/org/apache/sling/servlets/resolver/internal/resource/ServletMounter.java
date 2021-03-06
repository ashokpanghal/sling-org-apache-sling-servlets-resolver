/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.servlets.resolver.internal.resource;

import static org.apache.sling.api.servlets.ServletResolverConstants.SLING_SERVLET_NAME;
import static org.osgi.framework.Constants.SERVICE_ID;
import static org.osgi.framework.Constants.SERVICE_PID;
import static org.osgi.service.component.ComponentConstants.COMPONENT_NAME;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.sling.api.request.RequestUtil;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.ServletResolver;
import org.apache.sling.api.servlets.ServletResolverConstants;
import org.apache.sling.servlets.resolver.internal.ResolverConfig;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>SlingServletResolver</code> resolves a
 * servlet for a request by implementing the {@link ServletResolver} interface.
 *
 * The resolver uses an own session to find the scripts.
 *
 */
@Component(configurationPid = ResolverConfig.PID,
           service = {})
public class ServletMounter {

    /** Logger */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final String REF_SERVLET = "Servlet";

    private final ServletContext servletContext;

    private final Map<ServiceReference<Servlet>, ServletReg> servletsByReference = new HashMap<>();

    private volatile boolean active = true;

    private final ServletResourceProviderFactory servletResourceProviderFactory;

    /**
     * Activate this component.
     */
    @Activate
    public ServletMounter(final BundleContext context, @Reference final ResourceResolverFactory resourceResolverFactory,
            @Reference(target = "(name=org.apache.sling)") ServletContext servletContext,
            final ResolverConfig config) {
        this.servletContext = servletContext;
        servletResourceProviderFactory = new ServletResourceProviderFactory(config.servletresolver_servletRoot(),
                resourceResolverFactory.getSearchPath());
    }

    /**
     * Deactivate this component.
     */
    @Deactivate
    protected void deactivate() {
        this.active = false;
        // Copy the list of servlets first, to minimize the need for
        // synchronization
        final Collection<ServiceReference<Servlet>> refs;
        synchronized (this.servletsByReference) {
            refs = new ArrayList<>(servletsByReference.keySet());
        }
        // destroy all servlets
        destroyAllServlets(refs);

        // sanity check: clear array (it should be empty now anyway)
        synchronized ( this.servletsByReference ) {
            this.servletsByReference.clear();
        }
    }

    @Reference(
            name = REF_SERVLET,
            service = Servlet.class,
            cardinality = ReferenceCardinality.MULTIPLE,
            policy = ReferencePolicy.DYNAMIC,
            target="(|(" + ServletResolverConstants.SLING_SERVLET_PATHS + "=*)(" + ServletResolverConstants.SLING_SERVLET_RESOURCE_TYPES + "=*))")
    protected void bindServlet(final Servlet servlet, final ServiceReference<Servlet> reference) {
        if (this.active) {
            createServlet(servlet, reference);
        }
    }

    protected void unbindServlet(final ServiceReference<Servlet> reference) {
        destroyServlet(reference);
    }

    private boolean createServlet(final Servlet servlet, final ServiceReference<Servlet> reference) {
        // check for a name, this is required
        final String name = getName(reference);

        // check for Sling properties in the service registration
        final ServletResourceProvider provider = servletResourceProviderFactory.create(reference, servlet);
        if (provider == null) {
            // this is expected if the servlet is not destined for Sling
            return false;
        }

        // initialize now
        try {
            servlet.init(new SlingServletConfig(servletContext, reference, name));
            logger.debug("bindServlet: Servlet {} initialized", name);
        } catch (final ServletException ce) {
            logger.error("bindServlet: Servlet " + ServletResourceProviderFactory.getServiceReferenceInfo(reference) + " failed to initialize", ce);
            return false;
        } catch (final Throwable t) {
            logger.error("bindServlet: Unexpected problem initializing servlet " + ServletResourceProviderFactory.getServiceReferenceInfo(reference), t);
            return false;
        }

        boolean registered = false;
        final Bundle bundle = reference.getBundle();
        if ( bundle != null ) {
            final BundleContext bundleContext = bundle.getBundleContext();
            if ( bundleContext != null ) {
                final List<ServiceRegistration<ResourceProvider<Object>>> regs = new ArrayList<>();
                try {
                    for(final String root : provider.getServletPaths()) {
                        @SuppressWarnings("unchecked")
                        final ServiceRegistration<ResourceProvider<Object>> reg = (ServiceRegistration<ResourceProvider<Object>>) bundleContext.registerService(
                            ResourceProvider.class.getName(),
                            provider,
                            createServiceProperties(reference, root));
                        regs.add(reg);
                    }
                    registered = true;
                } catch ( final IllegalStateException ise ) {
                    // bundle context not valid anymore - ignore and continue without this
                }
                if ( registered ) {
                    if ( logger.isDebugEnabled() ) {
                        logger.debug("Registered {}", provider);
                    }
                    synchronized (this.servletsByReference) {
                        servletsByReference.put(reference, new ServletReg(servlet, regs));
                    }
                }
            }
        }
        if ( !registered ) {
            logger.debug("bindServlet: servlet has been unregistered in the meantime. Ignoring {}", name);
        }

        return true;
    }

    private Dictionary<String, Object> createServiceProperties(final ServiceReference<Servlet> reference,
            final String root) {

        final Dictionary<String, Object> params = new Hashtable<>();
        params.put(ResourceProvider.PROPERTY_ROOT, root);
        params.put(Constants.SERVICE_DESCRIPTION,
            "ServletResourceProvider for Servlet at " + root);

        // inherit service ranking
        Object rank = reference.getProperty(Constants.SERVICE_RANKING);
        if (rank instanceof Integer) {
            params.put(Constants.SERVICE_RANKING, rank);
        }

        return params;
    }

    private void destroyAllServlets(final Collection<ServiceReference<Servlet>> refs) {
        for (ServiceReference<Servlet> serviceReference : refs) {
            destroyServlet(serviceReference);
        }
    }

    private void destroyServlet(final ServiceReference<Servlet> reference) {
        ServletReg registration;
        synchronized (this.servletsByReference) {
            registration = servletsByReference.remove(reference);
        }
        if (registration != null) {

            for(final ServiceRegistration<ResourceProvider<Object>> reg : registration.registrations) {
                try {
                    reg.unregister();
                } catch ( final IllegalStateException ise) {
                    // this might happen on shutdown
                }
            }
            final String name = RequestUtil.getServletName(registration.servlet);
            logger.debug("unbindServlet: Servlet {} removed", name);

            try {
                registration.servlet.destroy();
            } catch (Throwable t) {
                logger.error("unbindServlet: Unexpected problem destroying servlet " + name, t);
            }
        }
    }

    /** The list of property names checked by {@link #getName(ServiceReference)} */
    private static final String[] NAME_PROPERTIES = { SLING_SERVLET_NAME,
        COMPONENT_NAME, SERVICE_PID, SERVICE_ID };

    /**
     * Looks for a name value in the service reference properties. See the
     * class comment at the top for the list of properties checked by this
     * method.
     * @return The servlet name. This method never returns {@code null}
     */
    private static String getName(final ServiceReference<Servlet> reference) {
        String servletName = null;
        for (int i = 0; i < NAME_PROPERTIES.length
            && (servletName == null || servletName.length() == 0); i++) {
            Object prop = reference.getProperty(NAME_PROPERTIES[i]);
            if (prop != null) {
                servletName = String.valueOf(prop);
            }
        }
        return servletName;
    }

    private static final class ServletReg {
        public final Servlet servlet;
        public final List<ServiceRegistration<ResourceProvider<Object>>> registrations;

        public ServletReg(final Servlet s, final List<ServiceRegistration<ResourceProvider<Object>>> srs) {
            this.servlet = s;
            this.registrations = srs;
        }
    }
}
