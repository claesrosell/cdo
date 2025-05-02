/*
 * Copyright (c) 2009-2013, 2015, 2016, 2018, 2019, 2023 Eike Stepper (Loehne, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 *    Stefan Winkler - major refactoring
 *    Stefan Winkler - 249610: [DB] Support external references (Implementation)
 *    Stefan Winkler - Bug 329025: [DB] Support branching for range-based mapping strategy
 */
package org.eclipse.emf.cdo.server.internal.db.mapping.horizontal;

import org.eclipse.emf.cdo.common.branch.CDOBranch;
import org.eclipse.emf.cdo.common.branch.CDOBranchPoint;
import org.eclipse.emf.cdo.common.branch.CDOBranchVersion;
import org.eclipse.emf.cdo.common.id.CDOID;
import org.eclipse.emf.cdo.common.revision.CDOList;
import org.eclipse.emf.cdo.common.revision.CDORevision;
import org.eclipse.emf.cdo.common.revision.CDORevisionHandler;
import org.eclipse.emf.cdo.common.revision.delta.CDOContainerFeatureDelta;
import org.eclipse.emf.cdo.common.revision.delta.CDOListFeatureDelta;
import org.eclipse.emf.cdo.common.revision.delta.CDOSetFeatureDelta;
import org.eclipse.emf.cdo.common.revision.delta.CDOUnsetFeatureDelta;
import org.eclipse.emf.cdo.eresource.EresourcePackage;
import org.eclipse.emf.cdo.server.IStoreAccessor.QueryXRefsContext;
import org.eclipse.emf.cdo.server.StoreThreadLocal;
import org.eclipse.emf.cdo.server.db.IDBStoreAccessor;
import org.eclipse.emf.cdo.server.db.IIDHandler;
import org.eclipse.emf.cdo.server.db.mapping.IClassMappingDeltaSupport;
import org.eclipse.emf.cdo.server.db.mapping.IClassMappingUnitSupport;
import org.eclipse.emf.cdo.server.db.mapping.IListMapping;
import org.eclipse.emf.cdo.server.db.mapping.IListMappingDeltaSupport;
import org.eclipse.emf.cdo.server.db.mapping.IListMappingUnitSupport;
import org.eclipse.emf.cdo.server.db.mapping.ITypeMapping;
import org.eclipse.emf.cdo.server.internal.db.DBStore;
import org.eclipse.emf.cdo.server.internal.db.bundle.OM;
import org.eclipse.emf.cdo.server.internal.db.mapping.horizontal.AbstractBasicListTableMapping.AbstractListDeltaWriter.NewListSizeResult;
import org.eclipse.emf.cdo.spi.common.revision.InternalCDORevision;
import org.eclipse.emf.cdo.spi.common.revision.InternalCDORevisionDelta;
import org.eclipse.emf.cdo.spi.common.revision.StubCDORevision;
import org.eclipse.emf.cdo.spi.server.InternalRepository;

import org.eclipse.net4j.db.DBException;
import org.eclipse.net4j.db.DBUtil;
import org.eclipse.net4j.db.IDBPreparedStatement;
import org.eclipse.net4j.db.IDBPreparedStatement.ReuseProbability;
import org.eclipse.net4j.db.IDBResultSet;
import org.eclipse.net4j.db.ddl.IDBField;
import org.eclipse.net4j.util.ImplementationError;
import org.eclipse.net4j.util.WrappedException;
import org.eclipse.net4j.util.collection.MoveableList;
import org.eclipse.net4j.util.collection.Pair;
import org.eclipse.net4j.util.concurrent.ConcurrencyUtil;
import org.eclipse.net4j.util.concurrent.TimeoutRuntimeException;
import org.eclipse.net4j.util.om.monitor.OMMonitor;
import org.eclipse.net4j.util.om.monitor.OMMonitor.Async;
import org.eclipse.net4j.util.om.trace.ContextTracer;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author Eike Stepper
 * @since 2.0
 */
public class HorizontalNonAuditClassMapping extends AbstractHorizontalClassMapping implements IClassMappingDeltaSupport, IClassMappingUnitSupport
{
  private static final ContextTracer TRACER = new ContextTracer(OM.DEBUG, HorizontalNonAuditClassMapping.class);

  private static final ContextTracer TRACER_UNITS = new ContextTracer(OM.DEBUG_UNITS, HorizontalAuditClassMapping.class);

  private String sqlSelectAllObjectIDs;

  private String sqlSelectCurrentAttributes;

  private String sqlSelectCurrentVersion;

  private String sqlSelectCurrentUnit;

  private String sqlInsertAttributes;

  private String sqlUpdateAffix;

  private String sqlUpdatePrefix;

  private String sqlUpdateContainerPart;

  private String sqlDelete;

  private boolean hasLists;

  public HorizontalNonAuditClassMapping(AbstractHorizontalMappingStrategy mappingStrategy, EClass eClass)
  {
    super(mappingStrategy, eClass);
  }

  @Override
  protected void initSQLStrings()
  {
    hasLists = !getListMappings().isEmpty();

    super.initSQLStrings();

    // ----------- Select Revision ---------------------------
    sqlSelectCurrentAttributes = buildSelectSQL(false);

    // ----------- Select Revisions for Unit -----------------
    InternalRepository repository = (InternalRepository)getMappingStrategy().getStore().getRepository();
    if (repository.isSupportingUnits())
    {
      sqlSelectCurrentUnit = buildSelectSQL(true);
    }

    // ----------- Select Version ---------------------------
    StringBuilder builder = new StringBuilder();
    builder.append("SELECT "); //$NON-NLS-1$
    builder.append(versionField);
    builder.append(" FROM "); //$NON-NLS-1$
    builder.append(getTable());
    builder.append(" WHERE "); //$NON-NLS-1$
    builder.append(idField);
    builder.append("=?"); //$NON-NLS-1$
    sqlSelectCurrentVersion = builder.toString();

    // ----------- Insert Attributes -------------------------
    builder = new StringBuilder();
    builder.append("INSERT INTO "); //$NON-NLS-1$
    builder.append(getTable());
    builder.append("("); //$NON-NLS-1$
    builder.append(idField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(versionField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(createdField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(revisedField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(resourceField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(containerField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(featureField);
    appendTypeMappingNames(builder, getValueMappings());
    appendFieldNames(builder, getUnsettableFields());
    appendFieldNames(builder, getListSizeFields());
    builder.append(") VALUES (?, ?, ?, ?, ?, ?, ?"); //$NON-NLS-1$
    appendTypeMappingParameters(builder, getValueMappings());
    appendFieldParameters(builder, getUnsettableFields());
    appendFieldParameters(builder, getListSizeFields());
    builder.append(")"); //$NON-NLS-1$
    sqlInsertAttributes = builder.toString();

    // ----------- Select all unrevised Object IDs ------
    builder = new StringBuilder("SELECT "); //$NON-NLS-1$
    builder.append(idField);
    builder.append(" FROM "); //$NON-NLS-1$
    builder.append(getTable());
    sqlSelectAllObjectIDs = builder.toString();

    // ----------- Update attributes --------------------
    builder = new StringBuilder("UPDATE "); //$NON-NLS-1$
    builder.append(getTable());
    builder.append(" SET "); //$NON-NLS-1$
    builder.append(versionField);
    builder.append("=?, "); //$NON-NLS-1$
    builder.append(createdField);
    builder.append("=?"); //$NON-NLS-1$
    sqlUpdatePrefix = builder.toString();

    builder = new StringBuilder(", "); //$NON-NLS-1$
    builder.append(resourceField);
    builder.append("=?, "); //$NON-NLS-1$
    builder.append(containerField);
    builder.append("=?, "); //$NON-NLS-1$
    builder.append(featureField);
    builder.append("=? "); //$NON-NLS-1$
    sqlUpdateContainerPart = builder.toString();

    builder = new StringBuilder(" WHERE "); //$NON-NLS-1$
    builder.append(idField);
    builder.append("=? "); //$NON-NLS-1$
    sqlUpdateAffix = builder.toString();

    builder = new StringBuilder("DELETE FROM "); //$NON-NLS-1$
    builder.append(getTable());
    builder.append(" WHERE "); //$NON-NLS-1$
    builder.append(idField);
    builder.append("=? "); //$NON-NLS-1$
    sqlDelete = builder.toString();
  }

  private String buildSelectSQL(boolean forUnits)
  {
    // ----------- Select Revision ---------------------------
    StringBuilder builder = new StringBuilder();
    builder.append("SELECT "); //$NON-NLS-1$

    if (forUnits)
    {
      builder.append(idField);
      builder.append(", "); //$NON-NLS-1$
    }

    builder.append(versionField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(createdField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(revisedField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(resourceField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(containerField);
    builder.append(", "); //$NON-NLS-1$
    builder.append(featureField);
    appendTypeMappingNames(builder, getValueMappings());
    appendFieldNames(builder, getUnsettableFields());
    appendFieldNames(builder, getListSizeFields());
    builder.append(" FROM "); //$NON-NLS-1$
    builder.append(getTable());

    if (forUnits)
    {
      UnitMappingTable units = ((DBStore)getMappingStrategy().getStore()).getUnitMappingTable();

      builder.append(", "); //$NON-NLS-1$
      builder.append(units);
      builder.append(" WHERE "); //$NON-NLS-1$
      builder.append(idField);
      builder.append("="); //$NON-NLS-1$
      builder.append(units);
      builder.append("."); //$NON-NLS-1$
      builder.append(units.elem());
      builder.append(" AND "); //$NON-NLS-1$
      builder.append(units);
      builder.append("."); //$NON-NLS-1$
      builder.append(units.unit());
      builder.append("=?"); //$NON-NLS-1$
    }
    else
    {
      builder.append(" WHERE "); //$NON-NLS-1$
      builder.append(idField);
      builder.append("=?"); //$NON-NLS-1$
    }
    return builder.toString();

  }

  @Override
  protected void writeValues(IDBStoreAccessor accessor, InternalCDORevision revision)
  {
    IIDHandler idHandler = getMappingStrategy().getStore().getIDHandler();
    IDBPreparedStatement stmt = accessor.getDBConnection().prepareStatement(sqlInsertAttributes, ReuseProbability.HIGH);

    try
    {
      int column = 1;
      idHandler.setCDOID(stmt, column++, revision.getID());
      stmt.setInt(column++, revision.getVersion());
      stmt.setLong(column++, revision.getTimeStamp());
      stmt.setLong(column++, revision.getRevised());
      idHandler.setCDOID(stmt, column++, revision.getResourceID());
      idHandler.setCDOID(stmt, column++, (CDOID)revision.getContainerID());
      stmt.setInt(column++, revision.getContainingFeatureID());

      int isSetCol = column + getValueMappings().size();

      for (ITypeMapping mapping : getValueMappings())
      {
        EStructuralFeature feature = mapping.getFeature();
        if (feature.isUnsettable())
        {
          if (revision.getValue(feature) == null)
          {
            stmt.setBoolean(isSetCol++, false);

            // also set value column to default value
            mapping.setDefaultValue(stmt, column++);
            continue;
          }

          stmt.setBoolean(isSetCol++, true);
        }

        mapping.setValueFromRevision(stmt, column++, revision);
      }

      Map<EStructuralFeature, IDBField> listSizeFields = getListSizeFields();
      if (listSizeFields != null)
      {
        // isSetCol now points to the first listTableSize-column
        column = isSetCol;

        for (EStructuralFeature feature : listSizeFields.keySet())
        {
          CDOList list = revision.getListOrNull(feature);
          int size = list == null ? UNSET_LIST : list.size();
          stmt.setInt(column++, size);
        }
      }

      DBUtil.update(stmt, true);
    }
    catch (SQLException e)
    {
      throw new DBException(e);
    }
    finally
    {
      DBUtil.close(stmt);
    }
  }

  @Override
  public IDBPreparedStatement createObjectIDStatement(IDBStoreAccessor accessor)
  {
    if (TRACER.isEnabled())
    {
      TRACER.format("Created ObjectID Statement : {0}", sqlSelectAllObjectIDs); //$NON-NLS-1$
    }

    return accessor.getDBConnection().prepareStatement(sqlSelectAllObjectIDs, ReuseProbability.HIGH);
  }

  @Override
  public IDBPreparedStatement createResourceQueryStatement(IDBStoreAccessor accessor, CDOID folderId, String name, boolean exactMatch,
      CDOBranchPoint branchPoint)
  {
    if (getTable() == null)
    {
      return null;
    }

    long timeStamp = branchPoint.getTimeStamp();
    if (timeStamp != CDORevision.UNSPECIFIED_DATE)
    {
      throw new IllegalArgumentException("Non-audit store does not support explicit timeStamp in resource query"); //$NON-NLS-1$
    }

    EStructuralFeature nameFeature = EresourcePackage.eINSTANCE.getCDOResourceNode_Name();

    ITypeMapping nameValueMapping = getValueMapping(nameFeature);
    if (nameValueMapping == null)
    {
      throw new ImplementationError(nameFeature + " not found in ClassMapping " + this); //$NON-NLS-1$
    }

    StringBuilder builder = new StringBuilder();
    builder.append("SELECT "); //$NON-NLS-1$
    builder.append(idField);
    builder.append(" FROM "); //$NON-NLS-1$
    builder.append(getTable());
    builder.append(" WHERE "); //$NON-NLS-1$
    builder.append(versionField);
    builder.append(">0 AND "); //$NON-NLS-1$
    builder.append(containerField);
    builder.append("=? AND "); //$NON-NLS-1$
    builder.append(nameValueMapping.getField());
    if (name == null)
    {
      builder.append(" IS NULL"); //$NON-NLS-1$
    }
    else
    {
      builder.append(exactMatch ? "=? " : " LIKE ? "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    IIDHandler idHandler = getMappingStrategy().getStore().getIDHandler();
    IDBPreparedStatement stmt = accessor.getDBConnection().prepareStatement(builder.toString(), ReuseProbability.MEDIUM);

    try
    {
      int column = 1;
      idHandler.setCDOID(stmt, column++, folderId);

      if (name != null)
      {
        String queryName = exactMatch ? name : name + "%"; //$NON-NLS-1$
        nameValueMapping.setValue(stmt, column++, queryName);
      }

      if (TRACER.isEnabled())
      {
        TRACER.format("Created Resource Query: {0}", stmt.toString()); //$NON-NLS-1$
      }

      return stmt;
    }
    catch (Throwable ex)
    {
      DBUtil.close(stmt); // only release on error
      throw new DBException(ex);
    }
  }

  @Override
  public boolean readRevision(IDBStoreAccessor accessor, InternalCDORevision revision, int listChunk)
  {
    long timeStamp = revision.getTimeStamp();
    if (timeStamp != CDOBranchPoint.UNSPECIFIED_DATE)
    {
      throw new UnsupportedOperationException("Mapping strategy does not support audits"); //$NON-NLS-1$
    }

    CDOID id = revision.getID();

    DBStore store = (DBStore)getMappingStrategy().getStore();
    IIDHandler idHandler = store.getIDHandler();
    IDBPreparedStatement stmtVersion = null;
    IDBPreparedStatement stmtAttributes = accessor.getDBConnection().prepareStatement(sqlSelectCurrentAttributes, ReuseProbability.HIGH);

    try
    {
      if (hasLists)
      {
        // Reading all list rows of an object is not atomic.
        // After all row reads are done, check the revision version again (see below).
        stmtVersion = accessor.getDBConnection().prepareStatement(sqlSelectCurrentVersion, ReuseProbability.HIGH);
        stmtVersion.setMaxRows(1); // Optimization: only 1 row
        idHandler.setCDOID(stmtVersion, 1, id);
      }

      idHandler.setCDOID(stmtAttributes, 1, id);

      for (;;)
      {
        // Read singleval-attribute table always (even without modeled attributes!)
        boolean success = readValuesFromStatement(stmtAttributes, revision, accessor);

        if (hasLists)
        {
          // Read multival tables only if revision exists
          if (success)
          {
            int currentVersion;

            try
            {
              readLists(accessor, revision, listChunk);
              currentVersion = readVersion(stmtVersion);
            }
            catch (IndexOutOfBoundsException ex)
            {
              // A commit has appended list rows after the list size has been read in readValuesFromStatement().
              // Trigger start from scratch below.
              currentVersion = CDOBranchVersion.UNSPECIFIED_VERSION;
            }

            if (currentVersion != revision.getVersion())
            {
              // A commit has changed the revision while reading the lists. Start from scratch!
              revision.clearValues(); // Make sure that lists are recreated
              continue;
            }
          }
        }

        return success;
      }
    }
    catch (SQLException ex)
    {
      throw new DBException(ex);
    }
    finally
    {
      DBUtil.close(stmtAttributes);
      DBUtil.close(stmtVersion);
    }
  }

  private int readVersion(IDBPreparedStatement stmt)
  {
    ResultSet resultSet = null;

    try
    {
      resultSet = stmt.executeQuery();
      if (resultSet.next())
      {
        return resultSet.getInt(1);
      }

      return CDOBranchVersion.UNSPECIFIED_VERSION;
    }
    catch (SQLException ex)
    {
      throw new DBException(ex);
    }
    finally
    {
      DBUtil.close(resultSet);
    }
  }

  @Override
  protected void reviseOldRevision(IDBStoreAccessor accessor, CDOID id, CDOBranch branch, long timeStamp)
  {
    // do nothing
  }

  @Override
  protected String getListXRefsWhere(QueryXRefsContext context)
  {
    if (CDORevision.UNSPECIFIED_DATE != context.getTimeStamp())
    {
      throw new IllegalArgumentException("Non-audit mode does not support timestamp specification");
    }

    if (!context.getBranch().isMainBranch())
    {
      throw new IllegalArgumentException("Non-audit mode does not support branch specification");
    }

    return revisedField + "=0";
  }

  @Override
  protected void detachAttributes(IDBStoreAccessor accessor, CDOID id, int version, CDOBranch branch, long timeStamp, OMMonitor monitor)
  {
    rawDelete(accessor, id, version, branch, monitor);
    getMappingStrategy().removeObjectType(accessor, id);
  }

  @Override
  protected void rawDeleteAttributes(IDBStoreAccessor accessor, CDOID id, CDOBranch branch, int version, OMMonitor monitor)
  {
    IIDHandler idHandler = getMappingStrategy().getStore().getIDHandler();
    IDBPreparedStatement stmt = accessor.getDBConnection().prepareStatement(sqlDelete, ReuseProbability.HIGH);

    try
    {
      idHandler.setCDOID(stmt, 1, id);
      DBUtil.update(stmt, true);
    }
    catch (SQLException e)
    {
      throw new DBException(e);
    }
    finally
    {
      DBUtil.close(stmt);
    }
  }

  @Override
  public void writeRevisionDelta(IDBStoreAccessor accessor, InternalCDORevisionDelta delta, long created, OMMonitor monitor)
  {
    Async async = null;
    monitor.begin();

    try
    {
      try
      {
        async = monitor.forkAsync();

        FeatureDeltaWriter writer = new FeatureDeltaWriter();
        writer.process(accessor, delta, created);
      }
      finally
      {
        if (async != null)
        {
          async.stop();
        }
      }
    }
    finally
    {
      monitor.done();
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class FeatureDeltaWriter extends AbstractFeatureDeltaWriter
  {
    private final List<Pair<ITypeMapping, Object>> attributeChanges = new ArrayList<>();

    private final List<Pair<EStructuralFeature, Integer>> listSizeChanges = new ArrayList<>();

    private int oldVersion;

    private boolean updateContainer;

    private int newContainingFeatureID;

    private CDOID newContainerID;

    private CDOID newResourceID;

    private int branchId;

    private int newVersion;

    @Override
    protected void doProcess(InternalCDORevisionDelta delta)
    {
      // Set context
      id = delta.getID();

      branchId = delta.getBranch().getID();
      oldVersion = delta.getVersion();
      newVersion = oldVersion + 1;

      // Process revision delta tree
      delta.accept(this);

      updateAttributes();
    }

    @Override
    public void visit(CDOSetFeatureDelta delta)
    {
      if (delta.getFeature().isMany())
      {
        throw new ImplementationError("Should not be called"); //$NON-NLS-1$
      }

      ITypeMapping am = getValueMapping(delta.getFeature());
      if (am == null)
      {
        throw new IllegalArgumentException("AttributeMapping for " + delta.getFeature() + " is null!"); //$NON-NLS-1$ //$NON-NLS-2$
      }

      attributeChanges.add(Pair.create(am, delta.getValue()));
    }

    @Override
    public void visit(CDOUnsetFeatureDelta delta)
    {
      // TODO: correct this when DBStore implements unsettable features
      // see Bugs 259868 and 263010
      ITypeMapping tm = getValueMapping(delta.getFeature());
      attributeChanges.add(Pair.create(tm, null));
    }

    @Override
    public void visit(CDOListFeatureDelta delta)
    {
      EStructuralFeature feature = delta.getFeature();
      int oldSize = delta.getOriginSize();
      int newSize = -1;

      try
      {
        IListMappingDeltaSupport listMapping = (IListMappingDeltaSupport)getListMapping(feature);
        listMapping.processDelta(accessor, id, branchId, oldVersion, oldVersion + 1, created, delta);
      }
      catch (NewListSizeResult result)
      {
        newSize = result.getNewListSize();
      }

      if (oldSize != newSize)
      {
        listSizeChanges.add(Pair.create(feature, newSize));
      }
    }

    @Override
    public void visit(CDOContainerFeatureDelta delta)
    {
      newContainingFeatureID = delta.getContainerFeatureID();
      newContainerID = (CDOID)delta.getContainerID();
      newResourceID = delta.getResourceID();
      updateContainer = true;
    }

    private void updateAttributes()
    {
      IIDHandler idHandler = getMappingStrategy().getStore().getIDHandler();
      String sql = buildUpdateSQL();
      IDBPreparedStatement stmt = accessor.getDBConnection().prepareStatement(sql, ReuseProbability.MEDIUM);

      try
      {
        int column = 1;
        stmt.setInt(column++, newVersion);
        stmt.setLong(column++, created);
        if (updateContainer)
        {
          idHandler.setCDOID(stmt, column++, newResourceID, created);
          idHandler.setCDOID(stmt, column++, newContainerID, created);
          stmt.setInt(column++, newContainingFeatureID);
        }

        column = setUpdateAttributeValues(attributeChanges, stmt, column);
        column = setUpdateListSizeChanges(listSizeChanges, stmt, column);

        idHandler.setCDOID(stmt, column++, id);

        DBUtil.update(stmt, true);
      }
      catch (SQLException e)
      {
        throw new DBException(e);
      }
      finally
      {
        DBUtil.close(stmt);
      }
    }

    private String buildUpdateSQL()
    {
      StringBuilder builder = new StringBuilder(sqlUpdatePrefix);
      if (updateContainer)
      {
        builder.append(sqlUpdateContainerPart);
      }

      for (Pair<ITypeMapping, Object> change : attributeChanges)
      {
        builder.append(", "); //$NON-NLS-1$
        ITypeMapping typeMapping = change.getElement1();
        builder.append(typeMapping.getField());
        builder.append("=?"); //$NON-NLS-1$

        if (typeMapping.getFeature().isUnsettable())
        {
          builder.append(", "); //$NON-NLS-1$
          builder.append(getUnsettableFields().get(typeMapping.getFeature()));
          builder.append("=?"); //$NON-NLS-1$
        }
      }

      for (Pair<EStructuralFeature, Integer> change : listSizeChanges)
      {
        builder.append(", "); //$NON-NLS-1$
        EStructuralFeature feature = change.getElement1();
        builder.append(getListSizeFields().get(feature));
        builder.append("=?"); //$NON-NLS-1$
      }

      builder.append(sqlUpdateAffix);
      return builder.toString();
    }

    private int setUpdateAttributeValues(List<Pair<ITypeMapping, Object>> attributeChanges, IDBPreparedStatement stmt, int col) throws SQLException
    {
      for (Pair<ITypeMapping, Object> change : attributeChanges)
      {
        ITypeMapping typeMapping = change.getElement1();
        Object value = change.getElement2();
        if (typeMapping.getFeature().isUnsettable())
        {
          // feature is unsettable
          if (value == null)
          {
            // feature is unset
            typeMapping.setDefaultValue(stmt, col++);
            stmt.setBoolean(col++, false);
          }
          else
          {
            // feature is set
            typeMapping.setValue(stmt, col++, value);
            stmt.setBoolean(col++, true);
          }
        }
        else
        {
          typeMapping.setValue(stmt, col++, change.getElement2());
        }
      }

      return col;
    }

    private int setUpdateListSizeChanges(List<Pair<EStructuralFeature, Integer>> attributeChanges, IDBPreparedStatement stmt, int col) throws SQLException
    {
      for (Pair<EStructuralFeature, Integer> change : listSizeChanges)
      {
        stmt.setInt(col++, change.getElement2());
      }

      return col;
    }
  }

  @Override
  public void readUnitRevisions(IDBStoreAccessor accessor, CDOBranchPoint branchPoint, CDOID rootID, CDORevisionHandler revisionHandler) throws SQLException
  {
    DBStore store = (DBStore)getMappingStrategy().getStore();
    InternalRepository repository = store.getRepository();

    CDOBranchPoint head = repository.getBranchManager().getMainBranch().getHead();
    EClass eClass = getEClass();
    long timeStamp = branchPoint.getTimeStamp();

    IIDHandler idHandler = store.getIDHandler();
    IDBPreparedStatement stmt = null;

    int jdbcFetchSize = store.getJDBCFetchSize();
    int oldFetchSize = -1;

    final long start1 = TRACER_UNITS.isEnabled() ? System.currentTimeMillis() : CDOBranchPoint.UNSPECIFIED_DATE;

    try
    {
      stmt = accessor.getDBConnection().prepareStatement(sqlSelectCurrentUnit, ReuseProbability.MEDIUM);
      idHandler.setCDOID(stmt, 1, rootID);

      AsnychronousListFiller listFiller = new AsnychronousListFiller(accessor, timeStamp, rootID, revisionHandler);
      ConcurrencyUtil.execute(repository, listFiller);

      oldFetchSize = stmt.getFetchSize();
      stmt.setFetchSize(jdbcFetchSize);
      IDBResultSet resultSet = stmt.executeQuery();

      for (;;)
      {
        InternalCDORevision revision = store.createRevision(eClass, null);
        revision.setBranchPoint(head);

        if (!readValuesFromResultSet(resultSet, idHandler, revision, true))
        {
          break;
        }

        listFiller.schedule(revision);
      }

      final long start2 = start1 != CDOBranchPoint.UNSPECIFIED_DATE ? System.currentTimeMillis() : start1;

      listFiller.await();

      if (start1 != CDOBranchPoint.UNSPECIFIED_DATE)
      {
        TRACER_UNITS.format("Read {0} revisions of unit {1}: {2} millis + {3} millis", eClass.getName(), rootID, start2 - start1,
            System.currentTimeMillis() - start2);
      }
    }
    finally
    {
      if (oldFetchSize != -1)
      {
        stmt.setFetchSize(oldFetchSize);
      }

      DBUtil.close(stmt);
    }
  }

  private class AsnychronousListFiller implements Runnable
  {
    private final BlockingQueue<InternalCDORevision> queue = new LinkedBlockingQueue<>();

    private final CountDownLatch latch = new CountDownLatch(1);

    private final IDBStoreAccessor accessor;

    private final long timeStamp;

    private final CDOID rootID;

    private final DBStore store;

    private final IIDHandler idHandler;

    private final IListMappingUnitSupport[] listMappings;

    private final ResultSet[] resultSets;

    private final CDORevisionHandler revisionHandler;

    private Throwable exception;

    public AsnychronousListFiller(IDBStoreAccessor accessor, long timeStamp, CDOID rootID, CDORevisionHandler revisionHandler)
    {
      this.accessor = accessor;
      this.timeStamp = timeStamp;
      this.rootID = rootID;
      this.revisionHandler = revisionHandler;

      store = (DBStore)accessor.getStore();
      idHandler = store.getIDHandler();

      List<IListMapping> tmp = getListMappings();
      int size = tmp.size();

      listMappings = new IListMappingUnitSupport[size];
      resultSets = new ResultSet[size];

      int i = 0;
      for (IListMapping listMapping : tmp)
      {
        listMappings[i++] = (IListMappingUnitSupport)listMapping;
      }
    }

    public void schedule(InternalCDORevision revision)
    {
      queue.offer(revision);
    }

    public void await() throws SQLException
    {
      // Schedule an end marker revision.
      schedule(new StubCDORevision(getEClass()));

      try
      {
        latch.await();
      }
      catch (InterruptedException ex)
      {
        throw new TimeoutRuntimeException();
      }
      finally
      {
        for (ResultSet resultSet : resultSets)
        {
          if (resultSet != null)
          {
            Statement statement = resultSet.getStatement();
            DBUtil.close(statement);
          }
        }
      }

      if (exception instanceof RuntimeException)
      {
        throw (RuntimeException)exception;
      }

      if (exception instanceof Error)
      {
        throw (Error)exception;
      }

      if (exception instanceof SQLException)
      {
        throw (SQLException)exception;
      }

      if (exception instanceof Exception)
      {
        throw WrappedException.wrap((Exception)exception);
      }
    }

    @Override
    public void run()
    {
      StoreThreadLocal.setAccessor(accessor);

      try
      {
        while (store.isActive())
        {
          InternalCDORevision revision = queue.poll(1, TimeUnit.SECONDS);
          if (revision == null)
          {
            continue;
          }

          if (revision instanceof StubCDORevision)
          {
            return;
          }

          readUnitEntries(revision);
        }
      }
      catch (Throwable ex)
      {
        exception = ex;
      }
      finally
      {
        latch.countDown();
        StoreThreadLocal.remove();
      }
    }

    private void readUnitEntries(InternalCDORevision revision) throws SQLException
    {
      CDOID id = revision.getID();

      for (int i = 0; i < listMappings.length; i++)
      {
        IListMappingUnitSupport listMapping = listMappings[i];
        EStructuralFeature feature = listMapping.getFeature();

        MoveableList<Object> list = revision.getListOrNull(feature);
        if (list != null)
        {
          int size = list.size();
          if (size != 0)
          {
            if (resultSets[i] == null)
            {
              resultSets[i] = listMapping.queryUnitEntries(accessor, idHandler, timeStamp, rootID);
            }

            listMapping.readUnitEntries(resultSets[i], idHandler, id, list);
          }
        }
      }

      synchronized (revisionHandler)
      {
        revisionHandler.handleRevision(revision);
      }
    }
  }

}
