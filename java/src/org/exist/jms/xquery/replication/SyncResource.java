/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2014 The eXist Project
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.jms.xquery.replication;


import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.collections.CollectionConfiguration;
import org.exist.collections.CollectionConfigurationException;
import org.exist.collections.CollectionConfigurationManager;
import org.exist.collections.triggers.DocumentTrigger;
import org.exist.collections.triggers.Trigger;
import org.exist.collections.triggers.TriggerException;
import org.exist.collections.triggers.TriggerProxy;
import org.exist.dom.QName;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.jms.replication.publish.ReplicationTrigger;
import org.exist.jms.shared.Constants;
import org.exist.jms.xquery.ReplicationModule;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.util.LockException;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.*;
import org.exist.xquery.value.*;

import java.util.List;
import java.util.Optional;

/**
 * Implementation of the replication:register() function.
 *
 * @author Dannes Wessels
 */
public class SyncResource extends BasicFunction {

    public final static FunctionSignature signatures[] = {
            new FunctionSignature(
                    new QName("sync", ReplicationModule.NAMESPACE_URI, ReplicationModule.PREFIX),
                    "Synchronize resource", new SequenceType[]{
                    new FunctionParameterSequenceType("path", Type.STRING, Cardinality.EXACTLY_ONE, "Path to resource"),},
                    new FunctionReturnSequenceType(Type.EMPTY, Cardinality.EMPTY, "No return value")
            ),
            new FunctionSignature(
                    new QName("sync-metadata", ReplicationModule.NAMESPACE_URI, ReplicationModule.PREFIX),
                    "Synchronize meta data of resource", new SequenceType[]{
                    new FunctionParameterSequenceType("path", Type.STRING, Cardinality.EXACTLY_ONE, "Path to resource"),},
                    new FunctionReturnSequenceType(Type.EMPTY, Cardinality.EMPTY, "No return value")
            ),};

    public SyncResource(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {

        // User must either be DBA or in the JMS group
        if (!context.getSubject().hasDbaRole() && !context.getSubject().hasGroup(Constants.JMS_GROUP)) {
            final String txt = String.format("Permission denied, user '%s' must be a DBA or be in group '%s'",
                    context.getSubject().getName(), Constants.JMS_GROUP);
            final XPathException ex = new XPathException(this, txt);
            LOG.error(txt, ex);
            throw ex;
        }

        final DBBroker broker = context.getBroker();
        final TransactionManager txnManager = broker.getBrokerPool().getTransactionManager();

        Collection parentCollection = null;

        boolean fullSync = isCalledAs("sync");

        try {

            // Get JMS configuration
            final String resource = args[0].itemAt(0).getStringValue();

            // Determine
            final XmldbURI sourcePathURI = XmldbURI.create(resource);
            final XmldbURI parentCollectionURI = sourcePathURI.removeLastSegment();
            final XmldbURI resourceURI = sourcePathURI.lastSegment();


            // Get parent collection
            /* Collection */ parentCollection = getCollection(broker, parentCollectionURI, false, true);

            // Get trigger, if existent
            Optional<ReplicationTrigger> replicationTrigger = getReplicationTrigger(broker, parentCollection);
            if (!replicationTrigger.isPresent()) {
                parentCollection.release(Lock.READ_LOCK);
                throw new XPathException(this, String.format("No trigger configuration found for collection %s", parentCollection));
            }
            ReplicationTrigger trigger = replicationTrigger.get();


            try (Txn txn = txnManager.beginTransaction()) {

                if (isCollection(broker, sourcePathURI)) {

                    // resource points to a collection
                    final Collection theCollection = getCollection(broker, sourcePathURI, true, false);

                    if(fullSync) {
                        trigger.afterCreateCollection(broker, txn, theCollection);
                    } else {
                        trigger.afterUpdateCollectionMetadata(broker, txn, theCollection);
                    }

                    theCollection.release(Lock.READ_LOCK);

                } else {
                    // resource points to a document
                    final DocumentImpl theDoc = getDocument(broker, parentCollectionURI, resourceURI);

                    if(fullSync) {
                        trigger.afterUpdateDocument(broker, txn, theDoc);
                    } else {
                        trigger.afterUpdateDocumentMetadata(broker, txn, theDoc);
                    }

                    theDoc.getUpdateLock().release(Lock.READ_LOCK);
                }

                // Cleanup
                txn.commit();
            }

        } catch (final XPathException ex) {

            LOG.error(ex.getMessage());
            ex.setLocation(this.line, this.column, this.getSource());
            throw ex;

        } catch (final Throwable t) {
            LOG.error(t);
            throw new XPathException(this, t);
        } finally {
            parentCollection.release(Lock.READ_LOCK);
        }


        return Sequence.EMPTY_SEQUENCE;
    }


    /**
     *  Retrieve configured replication trigger, when existent
     *
     * @param broker The broker
     * @param parentCollection The collection contaiing the resource
     * @return The trigger wrapped as optional
     */
    private Optional<ReplicationTrigger> getReplicationTrigger(DBBroker broker, Collection parentCollection) throws LockException, CollectionConfigurationException, EXistException, PermissionDeniedException, TriggerException {

        CollectionConfigurationManager cmm = new CollectionConfigurationManager(broker);
        CollectionConfiguration config = cmm.getOrCreateCollectionConfiguration(broker, parentCollection);

        // Iterate over list to find correct Trigger
        List<TriggerProxy<? extends DocumentTrigger>> triggerProxies = config.documentTriggers();
        for (TriggerProxy proxy : triggerProxies) {
            Trigger trigger = proxy.newInstance(broker, parentCollection);

            if (trigger instanceof ReplicationTrigger) {
                return Optional.of((ReplicationTrigger) trigger);
            }
        }

        return Optional.empty();
    }

    private Collection getCollection(DBBroker broker, XmldbURI collectionURI, boolean setReadLock, boolean throwExceptionWHenNotExistent) throws XPathException, PermissionDeniedException {

        Collection collection = broker.openCollection(collectionURI, setReadLock ? Lock.READ_LOCK : Lock.NO_LOCK);
        if (collection == null && throwExceptionWHenNotExistent) {
            throw new XPathException(this, String.format("Collection not found: %s", collectionURI));
        }
        return collection;

    }


    private boolean isCollection(DBBroker broker, XmldbURI collectionURI) throws XPathException, PermissionDeniedException {

        Collection collection = getCollection(broker, collectionURI, false, false);

        return collection != null;

    }

    private DocumentImpl getDocument(DBBroker broker, XmldbURI collectionURI, XmldbURI documentUri) throws XPathException, PermissionDeniedException {

        // Open collection if possible, else abort
        Collection collection = getCollection(broker, collectionURI, true, true);

        // Open document if possible, else abort
        final DocumentImpl resource = collection.getDocument(broker, documentUri);
        if (resource == null) {
            collection.getLock().release(Lock.READ_LOCK);
            throw new XPathException(this, String.format("No resource found for path: %s", documentUri));
        }

        return resource;

    }
}
