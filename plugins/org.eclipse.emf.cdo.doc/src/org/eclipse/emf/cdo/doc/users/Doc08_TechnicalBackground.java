/*
 * Copyright (c) 2015 Eike Stepper (Berlin, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 */
package org.eclipse.emf.cdo.doc.users;

import org.eclipse.emf.cdo.common.branch.CDOBranchPoint;
import org.eclipse.emf.cdo.common.id.CDOID;
import org.eclipse.emf.cdo.common.protocol.CDOProtocol;
import org.eclipse.emf.cdo.doc.online.CDOScalability;
import org.eclipse.emf.cdo.doc.online.EMFDeveloperGuide;
import org.eclipse.emf.cdo.doc.programmers.client.Doc02_PreparingModels;
import org.eclipse.emf.cdo.doc.programmers.client.Doc02_PreparingModels.Doc_CreatingEcore;
import org.eclipse.emf.cdo.doc.programmers.client.Doc02_PreparingModels.Doc_GeneratingModel;
import org.eclipse.emf.cdo.doc.programmers.client.Doc02_PreparingModels.Doc_MigratingManually;
import org.eclipse.emf.cdo.doc.users.Doc01_UserInterface.Doc_ProjectExplorerIntegration;
import org.eclipse.emf.cdo.doc.users.Doc01_UserInterface.Doc_SessionsView;
import org.eclipse.emf.cdo.doc.users.Doc02_ManagingRepositories.Doc_CreatingRepositories;
import org.eclipse.emf.cdo.doc.users.Doc02_ManagingRepositories.Doc_CreatingRepositories.Doc_LocalRepositories;
import org.eclipse.emf.cdo.doc.users.Doc02_ManagingRepositories.Doc_RepositoryShowIn.Doc_RepositoryShowInSystemExplorer;
import org.eclipse.emf.cdo.doc.users.Doc04_CheckingOut.Doc_CheckoutType.Doc_OfflineCheckouts;
import org.eclipse.emf.cdo.doc.users.Doc07_UsingModels.Doc_EditingModelElementsEditor;
import org.eclipse.emf.cdo.explorer.checkouts.CDOCheckout;
import org.eclipse.emf.cdo.explorer.repositories.CDORepository;
import org.eclipse.emf.cdo.server.IRepository;
import org.eclipse.emf.cdo.server.IStore;
import org.eclipse.emf.cdo.session.CDOSession;
import org.eclipse.emf.cdo.transaction.CDOTransaction;
import org.eclipse.emf.cdo.util.ReadOnlyException;
import org.eclipse.emf.cdo.view.CDOView;

import org.eclipse.emf.internal.cdo.CDOObjectImpl;

import org.eclipse.net4j.acceptor.IAcceptor;
import org.eclipse.net4j.connector.IConnector;
import org.eclipse.net4j.db.h2.H2Adapter;
import org.eclipse.net4j.tcp.ITCPConnector;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EPackage.Registry;
import org.eclipse.emf.spi.cdo.CDOSessionProtocol;

/**
 * Understanding the Technical Background
 * <p>
 * This article explains the relationship between the main concepts that are exposed in the
 * {@link Doc01_UserInterface CDO User Interface} and their underlying technical core concepts.
 * {@img tech-overview.png}
 * <p>
 * <b>Table of Contents</b> {@toc}
 *
 * @author Eike Stepper
 */
public class Doc08_TechnicalBackground
{
  /**
   * Technical Background of Model Elements
   * <p>
   * Model elements are {@link EObject EObjects}.
   * <p>
   * EObjects are instances of concrete {@link EClass EClasses}, sometimes referred to as model element types.
   * <p>
   * EClasses are contained by {@link EPackage EPackages}, often referred to as meta models and sometimes less specifically as just models.
   * <p>
   * EPackages are registered in the {@link Registry EPackage.Registry}.
   *
   * @see EMFDeveloperGuide
   */
  public class Doc_BackgroundModelElements
  {
    /**
     * Native Models
     * <p>
     * The term "native model" refers to an {@link EPackage} that is {@link Doc_GeneratingModel generated}
     * with some CDO-specific {@link Doc_MigratingManually options} to fully exploit CDO's unprecedented
     * characteristics with respect to scalability and performance.
     * <p>
     * Native model elements are lazily loaded when they are needed and automatically garbage-collected when they are no longer needed.
     * Repositories with native model elements can scale to arbitrary sizes. Clients may not be able to load all objects
     * of these large repositories at the same time, but they are able, for example, to iterate all objects of a large repository
     * without worrying about memory management.
     * <p>
     * Technically native model elements are instances of the Java class {@link CDOObjectImpl}.
     *
     * @see CDOScalability
     * @see Doc02_PreparingModels
     */
    public class Doc_BackgroundNativeModels
    {
    }

    /**
     * Legacy Models
     * <p>
     * Generating or regenerating an {@link EPackage} with the CDO-specific {@link Doc_MigratingManually options}
     * (as explained in {@link Doc_BackgroundNativeModels}) is not always possible, for example if an EPackage has already
     * been generated by a third party. In these cases the original generated EPackage can still be used with CDO;
     * and is then referred to as a "legacy model".
     * <p>
     * The integration of legacy models with CDO is based on CDOLegacyAdapters.
     * <p>
     * Legacy model elements are not loaded lazily and not automatically garbage-collected.
     */
    public class Doc_BackgroundLegacyModels
    {
    }

    /**
     * Dynamic Models
     * <p>
     * It is not strictly necessary for an {@link EPackage} to be generated into Java code to be used with CDO.
     * An EPackage can also be loaded dynamically at runtime (see {@link Doc_CreatingEcore} for an example of the
     * XML representation of an EPackage).
     * <p>
     * Technically dynamic model elements are instances of the Java class DynamicCDOObjectImpl.
     * <p>
     * Dynamic model elements share the characteristics of {@link Doc_BackgroundNativeModels native} model elements
     * with respect to enhanced scalability and performance,
     */
    public class Doc_BackgroundDynamicModels
    {
    }
  }

  /**
   * Technical Background of Repositories
   * <p>
   * The term "repository" is a slightly ambiguous in CDO, as it may refer to both a server-side / core-level {@link IRepository}
   * and a client-side / UI-level {@link CDORepository}.
   * <p>
   * An IRepository is a "real" repository backed by a physical database (of one of various {@link IStore forms)}. In production
   * such repositories typically exist in a CDO server
   * that provides remote access through one or more {@link ITCPConnector ITCPConnectors}.
   * The {@link org.eclipse.emf.cdo.doc.operators} explains how to configure and operate a CDO server.
   * <p>
   * A CDORepository is more of a {@link Doc_CreatingRepositories configured connection} to a "real" IRepository, which
   * is remembered across Eclipse sessions. In the case of a {@link Doc_LocalRepositories local repository} (connection)
   * an internal IRepository is created with an {@link H2Adapter H2} database {@link Doc_RepositoryShowInSystemExplorer stored on the local disk}.
   * <p>
   * Internally a {@link CDORepository#isConnected() connected} CDORepository maintains a single {@link CDOSession} to the underlying IRepository.
   * This session is shared by all {@link CDOView views} and {@link CDOTransaction transactions} of all {@link CDOCheckout checkouts}
   * from that CDORepository.
   *
   * @see Doc02_ManagingRepositories
   * @see Doc_BackgroundSessions
   */
  public class Doc_BackgroundRepositories
  {
  }

  /**
   * Technical Background of Checkouts
   * <p>
   * A {@link CDOCheckout} is not necessarily a physical copy of a repository on the local disk (only {@link Doc_OfflineCheckouts offline checkouts}
   * maintain a locally replicated repository copy). More generally they represent the following two aspects:
   * <ul>
   * <li> A reference to a configured {@link CDORepository} as a way to use the internal {@link CDOSession} of that CDORepository.
   * <li> The {@link CDOBranchPoint} information, i.e., {@link CDOBranchPoint#getBranch() branch} and {@link CDOBranchPoint#getTimeStamp() time stamp},
   *      that is needed to open {@link CDOView CDOViews} and {@link CDOTransaction CDOTransactions} on the shared CDOSession of
   *      the referenced CDORepository
   * </ul>
   * <p>
   * A CDOCheckout internally maintains a main CDOView that is, for example, used to provide the resources and model elements that
   * are displayed in the {@link Doc_ProjectExplorerIntegration Project Explorer}. As objects that are provided by CDOViews are read-only
   * any modification action on these objects, for example as offered in the various context menus or triggered by drag and drop events,
   * operates on {@link CDOView#getObject(EObject) transactional copies} of the objects in the context of a background thread.
   * <p>
   * Each {@link Doc_EditingModelElementsEditor model editor} opened on a resource or model element of a CDOCheckout typically
   * (but depending on the implementation of that editor) maintains its own CDOTransaction to isolate changes and locks from other
   * views and transactions. Typically the save action of a model editor delegates directly to the {@link CDOTransaction#commit(org.eclipse.core.runtime.IProgressMonitor) commit}
   * method of its associated CDOTransaction.
   */
  public class Doc_BackgroundCheckouts
  {
  }

  /**
   * Technical Background of Sessions
   * <p>
   * A {@link CDOSession} is the technical representation of a {@link CDOProtocol} connection to an {@link IRepository}.
   * On the transport level this connection is provided by an {@link IConnector} / {@link IAcceptor} pair.
   * {@img tech-sessions.png}
   *
   * @see Doc_SessionsView
   * @see Doc_BackgroundViews
   * @see Doc_BackgroundTransactions
   */
  public class Doc_BackgroundSessions
  {
  }

  /**
   * Technical Background of Views
   * <p>
   * A {@link CDOView} is a technical facility that provides a client application with all the models and model elements in a repository
   * for a specific {@link CDOBranchPoint#getTimeStamp() point in time} and in a specific {@link CDOBranchPoint#getBranch() branch}.
   * The model elements provided by a CDOView are {@link ReadOnlyException read-only}.
   * {@img tech-views.png}
   *
   * @see Doc_BackgroundSessions
   * @see Doc_BackgroundCheckouts
   */
  public class Doc_BackgroundViews
  {
  }

  /**
   * Technical Background of Transactions
   * <p>
   * A {@link CDOTransaction} is a technical facility that provides a client application with all the latest models
   * and model elements in a repository in a specific {@link CDOBranchPoint#getBranch() branch}.
   * The model elements provided by a CDOTransaction are writable.
   * Changes to these model elements must be {@link CDOTransaction#commit(org.eclipse.core.runtime.IProgressMonitor) committed} to make them
   * persistent in the repository and to distribute them to the views and transactions of other users.
   * {@img tech-transactions.png}
   *
   * @see Doc_BackgroundSessions
   * @see Doc_BackgroundCheckouts
   */
  public class Doc_BackgroundTransactions
  {
  }

  /**
   * Technical Background of the Compare Integration
   * <p>
   * With CDO both EMF Compare editors and EMF Merge editors are instrumented to utilize an optimized CDO mechanism in order to compute
   * matches in a very efficient and scalable way. This mechanism consists of special {@link CDOSessionProtocol#loadMergeData(org.eclipse.emf.cdo.spi.common.commit.CDORevisionAvailabilityInfo, org.eclipse.emf.cdo.spi.common.commit.CDORevisionAvailabilityInfo, org.eclipse.emf.cdo.spi.common.commit.CDORevisionAvailabilityInfo, org.eclipse.emf.cdo.spi.common.commit.CDORevisionAvailabilityInfo) client-server protocol}
   * and remote database queries to determine and deliver the {@link CDOID object IDs} that are involved in all changes
   * between two different {@link CDOBranchPoint CDOBranchPoints}. The response times depend on the implementation of the {@link IStore backend storage}.
   * The response time of the default implementation, the DBStore, scales more
   * with the sizes of the stored meta models (i.e., the number of concrete {@link EClass EClasses}) than with the sizes of the stored models
   * (i.e., the number of {@link EObject EObjects}).
   */
  public class Doc_BackgroundCompare
  {
  }
}