// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.wm.impl.status.widget;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointListener;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.openapi.wm.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public final class StatusBarWidgetsManager extends SimpleModificationTracker implements Disposable {
  private static final @NotNull Logger LOG = Logger.getInstance(StatusBar.class);

  private final Map<StatusBarWidgetFactory, StatusBarWidget> myWidgetFactories = new LinkedHashMap<>();
  private final Project myProject;

  public StatusBarWidgetsManager(@NotNull Project project) {
    myProject = project;
    Disposer.register(myProject, this);

    StatusBarWidgetFactory.EP_NAME.getPoint(null).addExtensionPointListener(new ExtensionPointListener<StatusBarWidgetFactory>() {
      @Override
      public void extensionAdded(@NotNull StatusBarWidgetFactory factory, @NotNull PluginDescriptor pluginDescriptor) {
        addWidgetFactory(factory);
      }

      @Override
      public void extensionRemoved(@NotNull StatusBarWidgetFactory factory, @NotNull PluginDescriptor pluginDescriptor) {
        removeWidgetFactory(factory);
      }
    }, true, this);

    StatusBarWidgetProvider.EP_NAME.getPoint(null).addExtensionPointListener(new ExtensionPointListener<StatusBarWidgetProvider>() {
      @Override
      public void extensionAdded(@NotNull StatusBarWidgetProvider provider, @NotNull PluginDescriptor pluginDescriptor) {
        addWidgetFactory(new StatusBarWidgetProviderToFactoryAdapter(myProject, provider));
      }

      @Override
      public void extensionRemoved(@NotNull StatusBarWidgetProvider provider, @NotNull PluginDescriptor pluginDescriptor) {
        removeWidgetFactory(new StatusBarWidgetProviderToFactoryAdapter(myProject, provider));
      }
    }, true, this);
  }

  public void updateAllWidgets() {
    for (StatusBarWidgetFactory factory : myWidgetFactories.keySet()) {
      updateWidget(factory);
    }
  }

  public void updateWidget(@NotNull Class<? extends StatusBarWidgetFactory> factoryExtension) {
    StatusBarWidgetFactory factory = StatusBarWidgetFactory.EP_NAME.findExtension(factoryExtension);
    if (factory == null) {
      LOG.error("Factory is not registered as `com.intellij.statusBarWidgetFactory` extension: " + factoryExtension.getName());
      return;
    }
    updateWidget(factory);
  }

  public void updateWidget(@NotNull StatusBarWidgetFactory factory) {
    if (factory.isAvailable(myProject) && (!factory.isConfigurable() || myProject.getService(StatusBarWidgetSettings.class).isEnabled(factory))) {
      enableWidget(factory);
    }
    else {
      disableWidget(factory);
    }
  }

  public boolean wasWidgetCreated(@Nullable StatusBarWidgetFactory factory) {
    return myWidgetFactories.get(factory) != null;
  }

  @Override
  public void dispose() {
    myWidgetFactories.forEach((factory, createdWidget) -> {
      if (createdWidget != null) {
        factory.disposeWidget(createdWidget);
      }
    });
    myWidgetFactories.clear();
  }

  public Set<StatusBarWidgetFactory> getWidgetFactories() {
    return myWidgetFactories.keySet();
  }

  private void enableWidget(@NotNull StatusBarWidgetFactory factory) {
    if (!myWidgetFactories.containsKey(factory)) {
      LOG.error("Factory is not registered as `com.intellij.statusBarWidgetFactory` extension: " + factory.getId());
      return;
    }

    StatusBarWidget createdWidget = myWidgetFactories.get(factory);
    if (createdWidget != null) {
      // widget is already enabled
      return;
    }

    StatusBar statusBar = WindowManager.getInstance().getStatusBar(myProject);
    if (statusBar == null) {
      LOG.error("Cannot add a widget for project without root status bar: " + factory.getId());
      return;
    }

    StatusBarWidget widget = factory.createWidget(myProject);
    if (!widget.ID().equals(factory.getId())) {
      LOG.warn("Factory id doesn't match widget id. It may affect ordering: " + factory.getId());
    }
    myWidgetFactories.put(factory, widget);
    statusBar.addWidget(widget, getAnchor(factory), this);
    Disposer.register(this, () -> disableWidget(factory));
  }

  @NotNull
  private String getAnchor(@NotNull StatusBarWidgetFactory factory) {
    if (factory instanceof StatusBarWidgetProviderToFactoryAdapter) {
      return ((StatusBarWidgetProviderToFactoryAdapter)factory).getAnchor();
    }
    List<StatusBarWidgetFactory> factories = StatusBarWidgetFactory.EP_NAME.getExtensionList();
    int indexOf = factories.indexOf(factory);
    for (int i = indexOf + 1; i < factories.size(); i++) {
      StatusBarWidgetFactory nextFactory = factories.get(i);
      if (myWidgetFactories.get(nextFactory) != null) {
        return StatusBar.Anchors.before(nextFactory.getId());
      }
    }
    for (int i = indexOf - 1; i >= 0; i--) {
      StatusBarWidgetFactory prevFactory = factories.get(i);
      if (myWidgetFactories.get(prevFactory) != null) {
        return StatusBar.Anchors.after(prevFactory.getId());
      }
    }
    return StatusBar.Anchors.DEFAULT_ANCHOR;
  }

  private void disableWidget(@NotNull StatusBarWidgetFactory factory) {
    StatusBarWidget createdWidget = myWidgetFactories.put(factory, null);
    if (createdWidget != null) {
      factory.disposeWidget(createdWidget);
      StatusBar statusBar = WindowManager.getInstance().getStatusBar(myProject);
      if (statusBar != null) {
        statusBar.removeWidget(createdWidget.ID());
      }
    }
  }

  public boolean canBeEnabledOnStatusBar(@NotNull StatusBarWidgetFactory factory, @NotNull StatusBar statusBar) {
    return factory.isAvailable(myProject) && factory.isConfigurable() && factory.canBeEnabledOn(statusBar);
  }

  private void addWidgetFactory(@NotNull StatusBarWidgetFactory factory) {
    if (myWidgetFactories.containsKey(factory)) {
      LOG.error("Factory has been added already: " + factory.getId());
      return;
    }
    myWidgetFactories.put(factory, null);
    incModificationCount();
  }

  private void removeWidgetFactory(@NotNull StatusBarWidgetFactory factory) {
    disableWidget(factory);
    myWidgetFactories.remove(factory);
    incModificationCount();
  }
}
