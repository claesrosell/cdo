/*
 * Copyright (c) 2009-2013 Eike Stepper (Berlin, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 */
package org.eclipse.emf.cdo.explorer.ui.checkouts;

import org.eclipse.emf.cdo.CDOState;
import org.eclipse.emf.cdo.common.branch.CDOBranch;
import org.eclipse.emf.cdo.common.branch.CDOBranchPoint;
import org.eclipse.emf.cdo.eresource.CDOResource;
import org.eclipse.emf.cdo.eresource.CDOResourceFolder;
import org.eclipse.emf.cdo.eresource.CDOResourceNode;
import org.eclipse.emf.cdo.explorer.CDOExplorerUtil;
import org.eclipse.emf.cdo.explorer.checkouts.CDOCheckout;
import org.eclipse.emf.cdo.explorer.checkouts.CDOCheckout.ObjectType;
import org.eclipse.emf.cdo.explorer.repositories.CDORepository;
import org.eclipse.emf.cdo.explorer.ui.bundle.OM;
import org.eclipse.emf.cdo.session.CDOSession;
import org.eclipse.emf.cdo.transaction.CDOTransaction;
import org.eclipse.emf.cdo.ui.compare.CDOCompareEditorUtil;
import org.eclipse.emf.cdo.util.CDOUtil;

import org.eclipse.net4j.util.AdapterUtil;
import org.eclipse.net4j.util.ObjectUtil;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.util.EcoreUtil;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.ui.navigator.CommonDropAdapter;
import org.eclipse.ui.navigator.CommonDropAdapterAssistant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Eike Stepper
 */
public class CDOCheckoutDropAdapterAssistant extends CommonDropAdapterAssistant
{
  private static final EObject[] NO_OBJECTS = {};

  public CDOCheckoutDropAdapterAssistant()
  {
  }

  @Override
  public boolean isSupportedType(TransferData transferType)
  {
    return super.isSupportedType(transferType) || FileTransfer.getInstance().isSupportedType(transferType);
  }

  @Override
  public IStatus validateDrop(Object target, int dropOperation, TransferData transferType)
  {
    CDOBranchPoint branchPoint = getSelectedBranchPoint(target, transferType);
    if (branchPoint != null)
    {
      return Status.OK_STATUS;
    }

    if (dropOperation != DND.DROP_LINK)
    {
      Operation<?> operation = Operation.getFor(target, transferType);
      if (operation != null && operation.canDrop())
      {
        if (dropOperation == DND.DROP_MOVE && !operation.canMove())
        {
          getCommonDropAdapter().overrideOperation(DND.DROP_COPY);
        }

        return Status.OK_STATUS;
      }
    }

    return Status.CANCEL_STATUS;
  }

  @Override
  public IStatus handleDrop(CommonDropAdapter dropAdapter, DropTargetEvent dropTargetEvent, Object target)
  {
    if (target == null || dropTargetEvent.data == null)
    {
      return Status.CANCEL_STATUS;
    }

    TransferData transferType = dropAdapter.getCurrentTransfer();
    int dropOperation = dropAdapter.getCurrentOperation();

    CDOBranchPoint branchPoint = getSelectedBranchPoint(target, transferType);
    if (branchPoint != null)
    {
      CDOCheckout checkout = (CDOCheckout)target;
      if (dropOperation == DND.DROP_MOVE)
      {
        // Switch To (online) / Replace With (offline)
        checkout.setBranchPoint(branchPoint.getBranch().getID(), branchPoint.getTimeStamp());
      }
      else if (dropOperation == DND.DROP_COPY)
      {
        // Merge From (online + offline)
        CDORepository repository = checkout.getRepository();
        CDOBranchPoint left = checkout.getBranchPoint();
        CDOBranchPoint right = branchPoint;
        CDOCompareEditorUtil.openEditor(repository, repository, left, right, null, true);
      }
      else if (dropOperation == DND.DROP_LINK)
      {
        // Compare With (online + offline)
        CDORepository repository = checkout.getRepository();
        CDOBranchPoint left = checkout.getBranchPoint();
        CDOBranchPoint right = branchPoint;
        CDOCompareEditorUtil.openEditor(repository, left, right, null, true);
      }

      return Status.OK_STATUS;
    }

    Operation<?> operation = Operation.getFor(target, transferType);
    if (operation != null)
    {
      operation.drop(dropOperation == DND.DROP_COPY);
      return Status.OK_STATUS;
    }

    return Status.CANCEL_STATUS;
  }

  private static CDOBranchPoint getSelectedBranchPoint(Object target, TransferData transferType)
  {
    // Drag within Eclipse?
    if (LocalSelectionTransfer.getTransfer().isSupportedType(transferType))
    {
      ISelection selection = LocalSelectionTransfer.getTransfer().getSelection();
      if (target instanceof CDOCheckout && selection instanceof IStructuredSelection)
      {
        CDOCheckout checkout = (CDOCheckout)target;
        if (checkout.isOpen())
        {
          IStructuredSelection ssel = (IStructuredSelection)selection;

          if (ssel.size() == 1)
          {
            Object element = ssel.getFirstElement();
            if (element instanceof CDORepository)
            {
              CDORepository repository = (CDORepository)element;
              if (repository.isConnected())
              {
                element = repository.getSession().getBranchManager().getMainBranch();
              }
            }

            if (element instanceof CDOBranch)
            {
              CDOBranch branch = (CDOBranch)element;
              element = branch.getHead();
            }

            if (element instanceof CDOBranchPoint)
            {
              CDOBranchPoint branchPoint = (CDOBranchPoint)element;
              CDOSession session = CDOUtil.getSession(branchPoint);
              if (session == checkout.getView().getSession())
              {
                return branchPoint;
              }
            }
          }
        }
      }
    }

    return null;
  }

  private static EObject[] getSelectedObjects()
  {
    ISelection selection = LocalSelectionTransfer.getTransfer().getSelection();
    if (selection instanceof IStructuredSelection)
    {
      return getSelectedObjects((IStructuredSelection)selection);
    }

    return NO_OBJECTS;
  }

  private static EObject[] getSelectedObjects(IStructuredSelection selection)
  {
    List<EObject> selectedObjects = new ArrayList<EObject>();
    ObjectType firstObjectType = null;

    for (Iterator<?> it = selection.iterator(); it.hasNext();)
    {
      Object object = it.next();

      EObject eObject = AdapterUtil.adapt(object, EObject.class);
      if (eObject != null)
      {
        ObjectType objectType = ObjectType.valueFor(eObject);
        if (objectType == null || objectType == ObjectType.Root)
        {
          return NO_OBJECTS;
        }

        if (firstObjectType == null)
        {
          firstObjectType = objectType;
        }
        else
        {
          boolean firstIsObject = firstObjectType == ObjectType.Object;
          boolean isObject = objectType == ObjectType.Object;
          if (firstIsObject != isObject)
          {
            return NO_OBJECTS;
          }
        }

        selectedObjects.add(eObject);
      }
    }

    return selectedObjects.toArray(new EObject[selectedObjects.size()]);
  }

  /**
   * @author Eike Stepper
   */
  private static abstract class Operation<T extends EObject>
  {
    private final EObject[] objects;

    private final T target;

    public Operation(EObject[] objects, T target)
    {
      this.objects = objects;
      this.target = target;
    }

    public final EObject[] getObjects()
    {
      return objects;
    }

    public final T getTarget()
    {
      return target;
    }

    public boolean canDrop()
    {
      T target = getTarget();
      EObject[] objects = getObjects();

      CDOCheckout targetCheckout = CDOExplorerUtil.getCheckout(target);
      for (EObject object : objects)
      {
        CDOCheckout checkout = CDOExplorerUtil.getCheckout(object);
        if (checkout != targetCheckout)
        {
          return false;
        }
      }

      return true;
    }

    public boolean canMove()
    {
      T target = getTarget();
      EObject[] objects = getObjects();

      for (EObject object : objects)
      {
        if (object == target || CDOExplorerUtil.getParent(object) == target)
        {
          return false;
        }
      }

      LinkedList<Object> path = CDOExplorerUtil.getPath(target);
      if (path == null)
      {
        return false;
      }

      Set<Object> targetPath = new HashSet<Object>(path);

      for (EObject object : objects)
      {
        if (targetPath.contains(object))
        {
          return false;
        }
      }

      return true;
    }

    public final void drop(final boolean copy)
    {
      final String title = (copy ? "Copy " : "Move ")
          + (ObjectType.valueFor(objects[0]) == ObjectType.Object ? "objects" : "resource nodes");

      new Job(title)
      {
        @Override
        protected IStatus run(IProgressMonitor monitor)
        {
          monitor.beginTask(title, 110 + (copy ? objects.length + 1 : 0));

          CDOCheckout checkout = CDOExplorerUtil.getCheckout(objects[0]);
          CDOTransaction transaction = checkout.openTransaction();
          monitor.worked(1);

          try
          {
            EcoreUtil.Copier copier = null;
            if (copy)
            {
              copier = new EcoreUtil.Copier();
            }

            List<EObject> transactionalObjects = new ArrayList<EObject>();
            for (int i = 0; i < objects.length; i++)
            {
              EObject object = objects[i];
              EObject transactionalObject = transaction.getObject(object);

              if (copier != null)
              {
                transactionalObject = copier.copy(transactionalObject);
                monitor.worked(1);
              }

              transactionalObjects.add(transactionalObject);
            }

            if (copier != null)
            {
              copier.copyReferences();
              monitor.worked(1);
            }

            T transactionalTarget = transaction.getObject(target);

            insert(transactionalObjects, transactionalTarget, new SubProgressMonitor(monitor, 10));
            transaction.commit(new SubProgressMonitor(monitor, 100));
          }
          catch (Exception ex)
          {
            OM.LOG.error(ex);
          }
          finally
          {
            monitor.done();
            transaction.close();
          }

          return Status.OK_STATUS;
        }
      }.schedule();
    }

    protected abstract void insert(List<? extends EObject> objects, T target, IProgressMonitor monitor);

    @SuppressWarnings("unchecked")
    public static <T extends EObject> Operation<T> getFor(Object target, TransferData transferType)
    {
      // Drag within Eclipse?
      if (LocalSelectionTransfer.getTransfer().isSupportedType(transferType))
      {
        EObject[] selectedObjects = getSelectedObjects();
        if (selectedObjects.length != 0)
        {
          ObjectType objectType = ObjectType.valueFor(selectedObjects[0]);
          if (objectType == ObjectType.Object)
          {
            if (target instanceof CDOResource)
            {
              CDOResource resource = (CDOResource)target;
              if (!resource.isRoot())
              {
                return (Operation<T>)new ObjectToResource(selectedObjects, resource);
              }
            }

            if (target instanceof EObject && !(target instanceof CDOResourceNode))
            {
              return (Operation<T>)new ObjectToObject(selectedObjects, (EObject)target);
            }

            if (target instanceof CDOCheckout)
            {
              CDOCheckout checkout = (CDOCheckout)target;
              EObject rootObject = checkout.getRootObject();
              if (rootObject instanceof CDOResource)
              {
                return (Operation<T>)new ObjectToResource(selectedObjects, (CDOResource)rootObject);
              }

              if (!(rootObject instanceof CDOResourceNode))
              {
                return (Operation<T>)new ObjectToObject(selectedObjects, rootObject);
              }
            }
          }
          else
          {
            if (target instanceof CDOCheckout)
            {
              CDOCheckout checkout = (CDOCheckout)target;
              EObject rootObject = checkout.getRootObject();
              if (rootObject instanceof CDOResourceFolder)
              {
                return (Operation<T>)new ResourceNodeToFolder(selectedObjects, (CDOResourceFolder)rootObject);
              }

              if (rootObject instanceof CDOResource)
              {
                CDOResource resource = (CDOResource)rootObject;
                if (resource.isRoot())
                {
                  return (Operation<T>)new ResourceNodeToRootResource(selectedObjects, resource);
                }
              }
            }

            if (target instanceof CDOResourceFolder)
            {
              return (Operation<T>)new ResourceNodeToFolder(selectedObjects, (CDOResourceFolder)target);
            }
          }
        }
      }

      return null;
    }

    private static void setUniqueName(CDOResourceNode resourceNode, EList<? extends EObject> contents)
    {
      boolean nameConflict = false;
      String resourceName = resourceNode.getName();
      Set<String> names = new HashSet<String>();

      for (Object object : contents)
      {
        if (object != resourceNode)
        {
          String name = ((CDOResourceNode)object).getName();
          if (ObjectUtil.equals(name, resourceName))
          {
            nameConflict = true;
          }

          names.add(name);
        }
      }

      if (nameConflict)
      {
        String copyName = resourceName + "-copy";
        for (int i = 0; i < Integer.MAX_VALUE; i++)
        {
          String name = copyName;
          if (i != 0)
          {
            name += i;
          }

          if (!names.contains(name))
          {
            resourceNode.setName(name);
            return;
          }
        }
      }
    }

    /**
     * @author Eike Stepper
     */
    private static final class ObjectToResource extends Operation<CDOResource>
    {
      public ObjectToResource(EObject[] objects, CDOResource target)
      {
        super(objects, target);
      }

      @Override
      protected void insert(List<? extends EObject> objects, CDOResource target, IProgressMonitor monitor)
      {
        target.getContents().addAll(objects);
      }
    }

    /**
     * @author Eike Stepper
     */
    private static final class ObjectToObject extends Operation<EObject>
    {
      public ObjectToObject(EObject[] objects, EObject target)
      {
        super(objects, target);
      }

      @Override
      public boolean canDrop()
      {
        if (!super.canDrop())
        {
          return false;
        }

        final EObject target = getTarget();
        final EObject[] objects = getObjects();

        class FeatureUsage
        {
          private final int upperBound;

          private int size;

          public FeatureUsage(EReference feature)
          {
            upperBound = feature.getUpperBound();

            if (feature.isMany())
            {
              @SuppressWarnings("unchecked")
              List<EObject> list = (List<EObject>)target.eGet(feature);
              size = list.size();
            }
            else
            {
              if (feature.isUnsettable())
              {
                if (target.eIsSet(feature))
                {
                  size = 1;
                }
              }
              else
              {
                Object value = target.eGet(feature);
                if (value != null)
                {
                  size = 1;
                }
              }
            }
          }

          public boolean use()
          {
            return ++size <= upperBound || upperBound == -1;
          }
        }

        Map<EReference, FeatureUsage> usages = new HashMap<EReference, FeatureUsage>();
        for (EReference feature : target.eClass().getEAllContainments())
        {
          usages.put(feature, new FeatureUsage(feature));
        }

        for (EObject object : objects)
        {
          EReference feature = getTargetFeature(object, target);
          if (feature == null)
          {
            return false;
          }

          FeatureUsage usage = usages.get(feature);
          if (usage == null || !usage.use())
          {
            return false;
          }
        }

        return true;
      }

      @Override
      protected void insert(List<? extends EObject> objects, EObject target, IProgressMonitor monitor)
      {
        for (EObject object : objects)
        {
          EReference feature = getTargetFeature(object, target);
          if (feature.isMany())
          {
            @SuppressWarnings("unchecked")
            List<EObject> list = (List<EObject>)target.eGet(feature);
            list.add(object);
          }
          else
          {
          }
        }
      }

      private EReference getTargetFeature(EObject object, EObject target)
      {
        EClass eClass = object.eClass();
        for (EReference feature : target.eClass().getEAllContainments())
        {
          EClass referenceType = feature.getEReferenceType();
          if (referenceType.isSuperTypeOf(eClass))
          {
            return feature;
          }
        }

        return null;
      }
    }

    /**
     * @author Eike Stepper
     */
    private static final class ResourceNodeToRootResource extends Operation<CDOResource>
    {
      public ResourceNodeToRootResource(EObject[] objects, CDOResource target)
      {
        super(objects, target);
      }

      /**
       * TODO Consolidate with {@link ResourceNodeToFolder#insert(List, CDOResourceFolder, IProgressMonitor)}.
       */
      @Override
      protected void insert(List<? extends EObject> objects, CDOResource target, IProgressMonitor monitor)
      {
        EList<EObject> contents = target.getContents();
        for (EObject object : objects)
        {
          CDOResourceNode resourceNode = (CDOResourceNode)object;
          boolean copy = resourceNode.cdoState() == CDOState.TRANSIENT;
          contents.add(resourceNode);
          // resourceNode.setFolder(null);

          if (copy)
          {
            // The object must be attached before getParent() is called!
            if (CDOExplorerUtil.getParent(object) == target)
            {
              setUniqueName(resourceNode, contents);
            }
          }
        }
      }
    }

    /**
     * @author Eike Stepper
     */
    private static final class ResourceNodeToFolder extends Operation<CDOResourceFolder>
    {
      public ResourceNodeToFolder(EObject[] objects, CDOResourceFolder target)
      {
        super(objects, target);
      }

      @Override
      protected void insert(List<? extends EObject> objects, CDOResourceFolder target, IProgressMonitor monitor)
      {
        EList<CDOResourceNode> nodes = target.getNodes();
        for (EObject object : objects)
        {
          CDOResourceNode resourceNode = (CDOResourceNode)object;
          boolean copy = resourceNode.cdoState() == CDOState.TRANSIENT;
          nodes.add(resourceNode);
          // ((InternalCDOObject)resourceNode).eSetResource(null, null);

          if (copy)
          {
            // The resourceNode must be attached before getParent() is called!
            if (CDOExplorerUtil.getParent(resourceNode) == target)
            {
              setUniqueName(resourceNode, nodes);
            }
          }
        }
      }
    }
  }
}