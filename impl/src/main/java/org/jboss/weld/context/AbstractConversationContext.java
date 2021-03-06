/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2009 Sun Microsystems, Inc. All rights reserved.
 *
 * Use is subject to license terms.
 *
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.context;

import static org.jboss.weld.context.conversation.ConversationIdGenerator.CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME;
import static org.jboss.weld.logging.messages.ConversationMessage.NO_CONVERSATION_FOUND_TO_RESTORE;
import static org.jboss.weld.util.reflection.Reflections.cast;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.enterprise.context.ConversationScoped;
import javax.enterprise.inject.Instance;

import org.jboss.weld.Container;
import org.jboss.weld.context.beanstore.BoundBeanStore;
import org.jboss.weld.context.beanstore.ConversationNamingScheme;
import org.jboss.weld.context.beanstore.NamingScheme;
import org.jboss.weld.context.conversation.ConversationIdGenerator;
import org.jboss.weld.context.conversation.ConversationImpl;
import org.jboss.weld.literal.DestroyedLiteral;
import org.jboss.weld.logging.messages.ConversationMessage;
import org.jboss.weld.manager.BeanManagerImpl;


/**
 * The base of the conversation context, which can use a variety of storage
 * forms
 *
 * @author Pete Muir
 * @author Jozef Hartinger
 * @author George Sapountzis
 */
public abstract class AbstractConversationContext<R, S> extends AbstractBoundContext<R> implements ConversationContext {

    private static final String CURRENT_CONVERSATION_ATTRIBUTE_NAME = ConversationContext.class.getName() + ".currentConversation";
    public static final String CONVERSATIONS_ATTRIBUTE_NAME = ConversationContext.class.getName() + ".conversations";

    private static final long DEFAULT_TIMEOUT = 10 * 60 * 1000L;
    private static final long CONCURRENT_ACCESS_TIMEOUT = 1000L;
    private static final String PARAMETER_NAME = "cid";

    private final AtomicReference<String> parameterName;
    private final AtomicLong defaultTimeout;
    private final AtomicLong concurrentAccessTimeout;

    private final ThreadLocal<R> associated;

    private final Instance<ConversationContext> conversationContexts;

    private final BeanManagerImpl manager;

    public AbstractConversationContext() {
        super(true);
        this.parameterName = new AtomicReference<String>(PARAMETER_NAME);
        this.defaultTimeout = new AtomicLong(DEFAULT_TIMEOUT);
        this.concurrentAccessTimeout = new AtomicLong(CONCURRENT_ACCESS_TIMEOUT);
        this.associated = new ThreadLocal<R>();
        this.manager = Container.instance().deploymentManager();
        this.conversationContexts = manager.instance().select(ConversationContext.class);
    }

    public String getParameterName() {
        return parameterName.get();
    }

    public void setParameterName(String cid) {
        this.parameterName.set(cid);
    }

    public void setConcurrentAccessTimeout(long timeout) {
        this.concurrentAccessTimeout.set(timeout);
    }

    public long getConcurrentAccessTimeout() {
        return concurrentAccessTimeout.get();
    }

    public void setDefaultTimeout(long timeout) {
        this.defaultTimeout.set(timeout);
    }

    public long getDefaultTimeout() {
        return defaultTimeout.get();
    }

    public boolean associate(R request) {
        if (this.associated.get() == null) {
            this.associated.set(request);
            /*
            * We need to delay attaching the bean store until activate() is called
            * so that we can attach the correct conversation id
            */

            /*
            * We may need access to the conversation id generator and
            * conversations. If the session already exists, we can load it from
            * there, otherwise we can create a new conversation id generator and
            * conversations collection. If the the session exists when the request
            * is dissociated, then we store them in the session then.
            *
            * We always store the generator and conversation map in the request
            * for temporary usage.
            */
            if (getSessionAttribute(request, CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME, false) == null) {
                ConversationIdGenerator generator = new ConversationIdGenerator();
                setRequestAttribute(request, CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME, generator);
                setSessionAttribute(request, CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME, generator, false);
            } else {
                setRequestAttribute(request, CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME, getSessionAttribute(request, CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME, true));
            }

            if (getSessionAttribute(request, CONVERSATIONS_ATTRIBUTE_NAME, false) == null) {
                Map<String, ManagedConversation> conversations = new HashMap<String, ManagedConversation>();
                setRequestAttribute(request, CONVERSATIONS_ATTRIBUTE_NAME, conversations);
                setSessionAttribute(request, CONVERSATIONS_ATTRIBUTE_NAME, conversations, false);
            } else {
                setRequestAttribute(request, CONVERSATIONS_ATTRIBUTE_NAME, getSessionAttribute(request, CONVERSATIONS_ATTRIBUTE_NAME, true));
            }

            return true;
        } else {
            return false;
        }
    }

    public boolean dissociate(R request) {
        if (isAssociated() && this.associated.get() != null) {
            try {
                /*
                * If the session is available, store the conversation id generator and
                * conversations if necessary.
                */
                if (getSessionAttribute(request, CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME, false) == null) {
                    setSessionAttribute(request, CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME, getRequestAttribute(request, CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME), false);
                }
                if (getSessionAttribute(request, CONVERSATIONS_ATTRIBUTE_NAME, false) == null) {
                    setSessionAttribute(request, CONVERSATIONS_ATTRIBUTE_NAME, getRequestAttribute(request, CONVERSATIONS_ATTRIBUTE_NAME), false);
                }
                this.associated.set(null);
                return true;
            } finally {
                cleanup();
            }
        } else {
            return false;
        }
    }

    @Override
    public void activate() {
        this.activate(null);
    }

    protected void associateRequest() {
        ManagedConversation conversation = new ConversationImpl(conversationContexts);
        setRequestAttribute(getRequest(), CURRENT_CONVERSATION_ATTRIBUTE_NAME, conversation);

        // Set a temporary bean store, this will be attached at the end of the request if needed
        NamingScheme namingScheme = new ConversationNamingScheme(ConversationContext.class.getName(), "transient");
        setBeanStore(createRequestBeanStore(namingScheme, getRequest()));
        setRequestAttribute(getRequest(), ConversationNamingScheme.PARAMETER_NAME, namingScheme);
    }

    protected void associateRequest(String cid) {
        ManagedConversation conversation = getConversation(cid);
        setRequestAttribute(getRequest(), CURRENT_CONVERSATION_ATTRIBUTE_NAME, conversation);

        NamingScheme namingScheme = new ConversationNamingScheme(ConversationContext.class.getName(), cid);
        setBeanStore(createRequestBeanStore(namingScheme, getRequest()));
        getBeanStore().attach();
    }

    public void activate(String cid) {
        if (getBeanStore() == null) {
            if (!isAssociated()) {
                throw new IllegalStateException("Must call associate() before calling activate()");
            }
            // Activate the context
            super.setActive(true);

            // Attach the conversation
            // WELD-1315 Don't try to restore the long-running conversation if cid param is empty
            if (cid != null && !cid.isEmpty()) {
                ManagedConversation conversation = getConversation(cid);
                if (conversation != null) {
                    boolean lock = conversation.lock(getConcurrentAccessTimeout());
                    if (lock) {
                        associateRequest(cid);
                    } else {
                        // Associate the request with a new transient conversation
                        associateRequest();
                        throw new BusyConversationException(ConversationMessage.CONVERSATION_LOCK_TIMEDOUT, cid);
                    }
                } else {
                    // CDI 6.7.4 we must activate a new transient conversation before we throw the exception
                    associateRequest();
                    // Make sure that the conversation already exists
                    throw new NonexistentConversationException(NO_CONVERSATION_FOUND_TO_RESTORE, cid);
                }
            } else {
                associateRequest();
            }
        } else {
            throw new IllegalStateException("Context is already active");
        }
    }

    @Override
    public void deactivate() {
        // Disassociate from the current conversation
        if (getBeanStore() != null) {
            if (!isAssociated()) {
                throw new IllegalStateException("Must call associate() before calling deactivate()");
            }

            if (getCurrentConversation().isTransient()) {
                destroy();
            } else {
                try {
                    // Update the conversation timestamp
                    getCurrentConversation().touch();
                    if (!getBeanStore().isAttached()) {
                        /*
                        * This was a transient conversation at the beginning of the
                        * request, so we need to update the CID it uses, and attach
                        * it. We also add it to the conversations the session knows
                        * about.
                        */
                        if (!(getRequestAttribute(getRequest(), ConversationNamingScheme.PARAMETER_NAME) instanceof ConversationNamingScheme)) {
                            throw new IllegalStateException("Unable to find ConversationNamingScheme in the request, this conversation wasn't transient at the start of the request");
                        }
                        ((ConversationNamingScheme) getRequestAttribute(getRequest(), ConversationNamingScheme.PARAMETER_NAME)).setCid(getCurrentConversation().getId());

                        getBeanStore().attach();

                        getConversationMap().put(getCurrentConversation().getId(), getCurrentConversation());
                    }
                } finally {
                    getCurrentConversation().unlock();
                }
            }
            setBeanStore(null);
            // Clean up any expired conversations
            Iterator<Entry<String, ManagedConversation>> entryIterator = getConversationMap().entrySet().iterator();
            while (entryIterator.hasNext()) {
                Entry<String, ManagedConversation> entry = entryIterator.next();
                if (entry.getValue().isTransient()) {
                    destroyConversation(getSessionFromRequest(getRequest(), false), entry.getKey());
                    entryIterator.remove();
                }
            }
            // deactivate the context
            super.setActive(false);
        } else {
            throw new IllegalStateException("Context is not active");
        }
    }

    @Override
    public void invalidate() {
        for (ManagedConversation conversation : getConversations()) {
            if (isExpired(conversation)) {
                if (!conversation.isTransient()) {
                    conversation.end();
                }
            }
        }
    }

    public boolean destroy(S session) {
        // the context may be active
        // if it is, we need to re-attach the bean store once the other conversations are destroyed
        BoundBeanStore beanStore = getBeanStore();
        if (beanStore != null) {
            beanStore.detach();
        }

        try {
            if (getSessionAttributeFromSession(session, CONVERSATIONS_ATTRIBUTE_NAME) instanceof Map<?, ?>) {
                // if there are conversations to destroy
                Map<String, ManagedConversation> conversations = cast(getSessionAttributeFromSession(session, CONVERSATIONS_ATTRIBUTE_NAME));
                // if the context is not active, let's activate it
                setActive(true);

                for (ManagedConversation conversation : conversations.values()) {
                    String id = conversation.getId();
                    if (!conversation.isTransient()) {
                        // the currently associated conversation will be destroyed at the end of the current request
                        conversation.end();
                    }
                    if (!isCurrentConversation(id)) {
                        // a conversation that is not currently associated is destroyed immediately
                        destroyConversation(session, id);
                    }
                }
            }
            return true;
        } finally {
            setBeanStore(beanStore);
            setActive(beanStore != null);
            if (isActive()) {
                getBeanStore().attach();
            } else {
                cleanup();
            }
        }
    }

    private boolean isCurrentConversation(String id) {
        if (!isAssociated()) {
            return false;
        }
        return id !=null && id.equals(getCurrentConversation().getId());
    }

    protected void destroyConversation(S session, String id) {
        if (session != null) {
            // session can be null as we may have nothing in the session
            setBeanStore(createSessionBeanStore(new ConversationNamingScheme(ConversationContext.class.getName(), id), session));
            getBeanStore().attach();
            destroy();
            getBeanStore().detach();
            setBeanStore(null);
            manager.getGlobalLenientObserverNotifier().fireEvent(id, DestroyedLiteral.CONVERSATION);
        }
    }

    public String generateConversationId() {
        if (!isAssociated()) {
            throw new IllegalStateException("A request must be associated with the context in order to generate a conversation id");
        }
        if (!(getRequestAttribute(getRequest(), CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME) instanceof ConversationIdGenerator)) {
            throw new IllegalStateException("Unable to locate ConversationIdGenerator");
        }
        ConversationIdGenerator generator = (ConversationIdGenerator) getRequestAttribute(getRequest(), CONVERSATION_ID_GENERATOR_ATTRIBUTE_NAME);
        return generator.call();
    }

    private static boolean isExpired(ManagedConversation conversation) {
        return System.currentTimeMillis() > (conversation.getLastUsed() + conversation.getTimeout());
    }

    public ManagedConversation getConversation(String id) {
        return getConversationMap().get(id);
    }

    public Collection<ManagedConversation> getConversations() {
        return getConversationMap().values();
    }

    private Map<String, ManagedConversation> getConversationMap() {
        if (!isAssociated()) {
            throw new IllegalStateException("A request must be associated with the context in order to load the known conversations");
        }
        if (!(getRequestAttribute(getRequest(), CONVERSATIONS_ATTRIBUTE_NAME) instanceof Map<?, ?>)) {
            throw new IllegalStateException("Unable to load current conversations from the associated request, something went badly wrong when associate() was called");
        }
        return cast(getRequestAttribute(getRequest(), CONVERSATIONS_ATTRIBUTE_NAME));
    }

    public ManagedConversation getCurrentConversation() {
        if (!isAssociated()) {
            throw new IllegalStateException("A request must be associated with the context in order to load the known conversations");
        }
        if (!(getRequestAttribute(getRequest(), CURRENT_CONVERSATION_ATTRIBUTE_NAME) instanceof ManagedConversation)) {
            throw new IllegalStateException("Unable to load current conversations from the associated request, something went badly wrong when associate() was called");
        }
        return (ManagedConversation) getRequestAttribute(getRequest(), CURRENT_CONVERSATION_ATTRIBUTE_NAME);
    }

    public Class<? extends Annotation> getScope() {
        return ConversationScoped.class;
    }

    /**
     * Set an attribute in the session.
     *
     * @param request the request to set the session attribute in
     * @param name    the name of the attribute
     * @param value   the value of the attribute
     * @param create  if false, the attribute will only be set if the session
     *                already exists, other wise it will always be set
     * @throws IllegalStateException if create is true, and the session can't be
     *                               created
     */
    protected abstract void setSessionAttribute(R request, String name, Object value, boolean create);

    /**
     * Get an attribute value from the session.
     *
     * @param request the request to get the session attribute from
     * @param name    the name of the attribute
     * @param create  if false, the attribute will only be retrieved if the
     *                session already exists, other wise it will always be retrieved
     * @return attribute
     * @throws IllegalStateException if create is true, and the session can't be
     *                               created
     */
    protected abstract Object getSessionAttribute(R request, String name, boolean create);

    /**
     * Get an attribute value from the session.
     *
     * @param session the session to get the session attribute from
     * @param name    the name of the attribute
     * @return attribute
     * @throws IllegalStateException if create is true, and the session can't be
     *                               created
     */
    protected abstract Object getSessionAttributeFromSession(S session, String name);

    /**
     * Remove an attribute from the request.
     *
     * @param request the request to remove the attribute from
     * @param name    the name of the attribute
     */
    protected abstract void removeRequestAttribute(R request, String name);

    /**
     * Set an attribute in the request.
     *
     * @param request the request to set the attribute from
     * @param name    the name of the attribute
     * @param value   the value of the attribute
     */
    protected abstract void setRequestAttribute(R request, String name, Object value);

    /**
     * Retrieve an attribute value from the request
     *
     * @param request the request to get the attribute from
     * @param name    the name of the attribute to get
     * @return the value of the attribute
     */
    protected abstract Object getRequestAttribute(R request, String name);

    protected abstract BoundBeanStore createRequestBeanStore(NamingScheme namingScheme, R request);

    protected abstract BoundBeanStore createSessionBeanStore(NamingScheme namingScheme, S session);

    protected abstract S getSessionFromRequest(R request, boolean create);

    /**
     * Check if the context is currently associated
     *
     * @return true if the context is associated
     */
    private boolean isAssociated() {
        return associated.get() != null;
    }

    /**
     * Get the associated store
     *
     * @return the request
     */
    private R getRequest() {
        return associated.get();
    }

}
