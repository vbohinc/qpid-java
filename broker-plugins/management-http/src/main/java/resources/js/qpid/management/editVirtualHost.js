/*
 *
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
 *
 */
define(["dojox/html/entities",
        "dojo/_base/array",
        "dojo/_base/event",
        "dojo/_base/lang",
        "dojo/_base/window",
        "dojo/dom",
        "dojo/dom-construct",
        "dijit/registry",
        "dojo/parser",
        'dojo/json',
        "dojo/query",
        "dojo/store/Memory",
        "dojo/data/ObjectStore",
        "qpid/common/util",
        "dojo/text!editVirtualHost.html",
        "qpid/common/ContextVariablesEditor",
        "dijit/Dialog",
        "dijit/form/CheckBox",
        "dijit/form/FilteringSelect",
        "dijit/form/ValidationTextBox",
        "dijit/form/Button",
        "dijit/form/Form",
        "dojox/validate/us",
        "dojox/validate/web",
        "dojo/domReady!"],
  function (entities, array, event, lang, win, dom, domConstruct, registry, parser, json, query, Memory, ObjectStore, util, template)
  {
    var fields = [ "name", "connectionThreadPoolSize", "queue.deadLetterQueueEnabled",
                   "storeTransactionIdleTimeoutWarn", "storeTransactionIdleTimeoutClose", "storeTransactionOpenTimeoutWarn",
                   "storeTransactionOpenTimeoutClose", "housekeepingCheckPeriod", "housekeepingThreadCount"];
    var numericFieldNames = ["storeTransactionIdleTimeoutWarn", "storeTransactionIdleTimeoutClose",
                             "storeTransactionOpenTimeoutWarn", "storeTransactionOpenTimeoutClose",
                             "housekeepingCheckPeriod", "housekeepingThreadCount",
                             "connectionThreadPoolSize"];


    var virtualHostEditor =
    {
      init: function()
      {
        var that=this;
        this.containerNode = domConstruct.create("div", {innerHTML: template});
        parser.parse(this.containerNode).then(function(instances){ that._postParse();});
      },
      _postParse: function()
      {
        var that=this;
        this.allFieldsContainer = dom.byId("editVirtualHost.allFields");
        this.typeFieldsContainer = dom.byId("editVirtualHost.typeFields");
        this.dialog = registry.byId("editVirtualHostDialog");
        this.saveButton = registry.byId("editVirtualHost.saveButton");
        this.cancelButton = registry.byId("editVirtualHost.cancelButton");
        this.cancelButton.on("click", function(e){that._cancel(e);});
        this.saveButton.on("click", function(e){that._save(e);});
        for(var i = 0; i < fields.length; i++)
        {
            var fieldName = fields[i];
            this[fieldName] = registry.byId("editVirtualHost." + fieldName);
        }
        this.form = registry.byId("editVirtualHostForm");
        this.form.on("submit", function(){return false;});
      },
      show: function(management, modelObj)
      {
        this.management = management;
        this.modelObj = modelObj;
        var that=this;
        if (!this.context)
        {
         this.context = new qpid.common.ContextVariablesEditor({name: 'context', title: 'Context variables'});
         this.context.placeAt(dom.byId("editVirtualHost.context"));
        }
        this.dialog.set("title", "Edit Virtual Host - " + entities.encode(String(modelObj.name)));

        management.load(modelObj, { actuals: true }).then(
            function(actualData)
            {
                management.load(modelObj).then(
                    function(effectiveData)
                    {
                        that._show(actualData[0], effectiveData[0]);
                    },
                    util.xhrErrorHandler);
            });

      },
      destroy: function()
      {
        if (this.dialog)
        {
            this.dialog.destroyRecursive();
            this.dialog = null;
        }

        if (this.containerNode)
        {
            domConstruct.destroy(this.containerNode);
            this.containerNode = null;
        }
      },
      _cancel: function(e)
      {
          this.dialog.hide();
      },
      _save: function(e)
      {
          event.stop(e);
          if(this.form.validate())
          {
              var data = util.getFormWidgetValues(this.form, this.initialData);
              var context = this.context.get("value");
              if (context && !util.equals(context, this.initialData.context))
              {
                data["context"] = context;
              }
              var that=this;
              this.management.update(that.modelObj, data).then( function(x){ that.dialog.hide();} );
          }
          else
          {
              alert('Form contains invalid data.  Please correct first');
          }
      },
      _show:function(actualData, effectiveData)
      {

          this.initialData = actualData;
          for(var i = 0; i < fields.length; i++)
          {
            var fieldName = fields[i];
            var widget = this[fieldName];
            widget.reset();

            if (widget instanceof dijit.form.CheckBox)
            {
              widget.set("checked", actualData[fieldName]);
            }
            else
            {
              widget.set("value", actualData[fieldName]);
            }
          }

          util.setContextData(this.context, this.management, this.modelObj, actualData, effectiveData );

          // Add regexp to the numeric fields
          for(var i = 0; i < numericFieldNames.length; i++)
          {
            this[numericFieldNames[i]].set("regExpGen", util.numericOrContextVarRegexp);
          }

          var that = this;

          var widgets = registry.findWidgets(this.typeFieldsContainer);
          array.forEach(widgets, function(item) { item.destroyRecursive();});
          domConstruct.empty(this.typeFieldsContainer);

          require(["qpid/management/virtualhost/" + actualData.type.toLowerCase() + "/edit"],
             function(TypeUI)
             {
                try
                {
                    var metadata = that.management.metadata;
                    TypeUI.show({containerNode:that.typeFieldsContainer, parent: that, data: actualData, metadata: metadata});
                    that.form.connectChildren();

                    util.applyToWidgets(that.allFieldsContainer, "VirtualHost", actualData.type, actualData, metadata);
                }
                catch(e)
                {
                    if (console && console.warn )
                    {
                        console.warn(e);
                    }
                }
             }
          );

          this.dialog.startup();
          this.dialog.show();
          if (!this.resizeEventRegistered)
          {
            this.resizeEventRegistered = true;
            util.resizeContentAreaAndRepositionDialog(dom.byId("editVirtualHost.contentPane"), this.dialog);
          }
      }
    };

    virtualHostEditor.init();

    return virtualHostEditor;
  }
);
