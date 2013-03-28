/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013, Red Hat, Inc., and individual contributors
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
package org.jboss.weld.injection.producer;

import static org.jboss.weld.logging.messages.BeanMessage.INVOCATION_ERROR;

import java.lang.reflect.Method;
import java.security.AccessController;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.inject.spi.AnnotatedMethod;

import org.jboss.weld.annotated.enhanced.EnhancedAnnotatedType;
import org.jboss.weld.exceptions.WeldException;
import org.jboss.weld.interceptor.util.InterceptionUtils;
import org.jboss.weld.security.GetAccessibleCopyOfMember;
import org.jboss.weld.util.BeanMethods;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * If the component is not intercepted this implementation takes care of invoking its lifecycle callback methods. If the
 * component is interception, {@link PostConstruct} / {@link PreDestroy} invocation is delegated to the intercepting proxy.
 *
 * @author Jozef Hartinger
 *
 * @param <T>
 */
public class DefaultLifecycleCallbackInvoker<T> implements LifecycleCallbackInvoker<T> {

    private static final Function<AnnotatedMethod<?>, Method> ACCESSIBLE_METHOD_FUNCTION = new Function<AnnotatedMethod<?>, Method>() {
        @Override
        public Method apply(AnnotatedMethod<?> method) {
            return AccessController.doPrivileged(new GetAccessibleCopyOfMember<Method>(method.getJavaMember()));
        }
    };

    private final List<Method> accessiblePostConstructMethods;
    private final List<Method> accessiblePreDestroyMethods;

    public DefaultLifecycleCallbackInvoker(EnhancedAnnotatedType<T> type) {
        this.accessiblePostConstructMethods = initMethodList(BeanMethods.getPostConstructMethods(type));
        this.accessiblePreDestroyMethods = initMethodList(BeanMethods.getPreDestroyMethods(type));
    }

    private List<Method> initMethodList(List<? extends AnnotatedMethod<?>> methods) {
         return ImmutableList.copyOf(Lists.transform(methods, ACCESSIBLE_METHOD_FUNCTION));
    }

    @Override
    public void postConstruct(T instance, Instantiator<T> instantiator) {
        if (instantiator.hasInterceptorSupport()) {
            InterceptionUtils.executePostConstruct(instance);
        } else {
            invokeMethods(accessiblePostConstructMethods, instance);
        }
    }

    @Override
    public void preDestroy(T instance, Instantiator<T> instantiator) {
        if (instantiator.hasInterceptorSupport()) {
            InterceptionUtils.executePredestroy(instance);
        } else {
            invokeMethods(accessiblePreDestroyMethods, instance);
        }
    }

    private void invokeMethods(List<Method> methods, T instance) {
        for (Method method : methods) {
            try {
                method.invoke(instance);
            } catch (Exception e) {
                throw new WeldException(INVOCATION_ERROR, e, method, instance);
            }
        }
    }

    @Override
    public boolean hasPreDestroyMethods() {
        return !accessiblePreDestroyMethods.isEmpty();
    }

    @Override
    public boolean hasPostConstructMethods() {
        return !accessiblePostConstructMethods.isEmpty();
    }
}