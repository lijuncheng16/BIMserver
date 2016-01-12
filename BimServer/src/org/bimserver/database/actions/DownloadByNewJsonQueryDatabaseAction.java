package org.bimserver.database.actions;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bimserver.BimServer;
import org.bimserver.BimserverDatabaseException;
import org.bimserver.ServerIfcModel;
import org.bimserver.database.BimserverLockConflictException;
import org.bimserver.database.DatabaseSession;
import org.bimserver.database.OldQuery;
import org.bimserver.database.queries.QueryObjectProvider;
import org.bimserver.database.queries.om.JsonQueryObjectModelConverter;
import org.bimserver.database.queries.om.Query;
import org.bimserver.emf.IdEObject;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.emf.IfcModelInterfaceException;
import org.bimserver.emf.PackageMetaData;
import org.bimserver.models.log.AccessMethod;
import org.bimserver.models.store.PluginConfiguration;
import org.bimserver.models.store.Project;
import org.bimserver.models.store.Revision;
import org.bimserver.models.store.StorePackage;
import org.bimserver.models.store.User;
import org.bimserver.plugins.IfcModelSet;
import org.bimserver.shared.HashMapVirtualObject;
import org.bimserver.shared.QueryException;
import org.bimserver.shared.exceptions.UserException;
import org.bimserver.webservices.authorization.Authorization;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class DownloadByNewJsonQueryDatabaseAction extends AbstractDownloadDatabaseAction<IfcModelInterface> {

	private final Set<Long> roids;
	private int progress;
	private long serializerOid;
	private String json;

	public DownloadByNewJsonQueryDatabaseAction(BimServer bimServer, DatabaseSession databaseSession, AccessMethod accessMethod, Set<Long> roids, String json, long serializerOid, Authorization authorization) {
		super(bimServer, databaseSession, accessMethod, authorization);
		this.roids = roids;
		this.json = json;
		this.serializerOid = serializerOid;
	}

	@Override
	public IfcModelInterface execute() throws UserException, BimserverLockConflictException, BimserverDatabaseException {
		long roid = roids.iterator().next();
		Revision revision = getDatabaseSession().get(StorePackage.eINSTANCE.getRevision(), roid, OldQuery.getDefault());
		PackageMetaData packageMetaData = getBimServer().getMetaDataManager().getPackageMetaData(revision.getProject().getSchema());
		JsonQueryObjectModelConverter converter = new JsonQueryObjectModelConverter(packageMetaData);
		ObjectNode queryObject;
		try {
			queryObject = new ObjectMapper().readValue(json, ObjectNode.class);
		Query query = converter.parseJson("query", (ObjectNode) queryObject);

		IfcModelInterface ifcModel = new ServerIfcModel(packageMetaData, null, getDatabaseSession());

		QueryObjectProvider queryObjectProvider = new QueryObjectProvider(getDatabaseSession(), getBimServer(), query, roids, packageMetaData);
		HashMapVirtualObject next = queryObjectProvider.next();
		while (next != null) {
			IdEObject newObject = packageMetaData.create(next.eClass());
			for (EAttribute eAttribute : newObject.eClass().getEAllAttributes()) {
				newObject.eSet(eAttribute, next.eGet(eAttribute));
			}
			ifcModel.add(next.getOid(), newObject);
			next = queryObjectProvider.next();
		}

		queryObjectProvider = new QueryObjectProvider(getDatabaseSession(), getBimServer(), query, roids, packageMetaData);
		next = queryObjectProvider.next();
		while (next != null) {
			IdEObject idEObject = ifcModel.get(next.getOid());
			for (EReference eReference : idEObject.eClass().getEAllReferences()) {
				if (eReference.isMany()) {
					List<Long> refOids = (List<Long>)next.eGet(eReference);
					List<IdEObject> list = (List<IdEObject>)idEObject.eGet(eReference);
					if (refOids != null) {
						for (Long refOid : refOids) {
							IdEObject ref = ifcModel.get(refOid);
							if (ref != null) {
								list.add(ref);
							}
						}
					}
				} else {
					long refOid = (long) next.eGet(eReference);
					idEObject.eSet(eReference, ifcModel.get(refOid));
				}
			}
			next = queryObjectProvider.next();
		}
		
		IfcModelSet ifcModelSet = new IfcModelSet();
		User user = getUserByUoid(getAuthorization().getUoid());
		Project project = null;
		PluginConfiguration serializerPluginConfiguration = getDatabaseSession().get(StorePackage.eINSTANCE.getPluginConfiguration(), serializerOid, OldQuery.getDefault());
		String name = "";
		PackageMetaData lastPackageMetaData = null;
		Map<Integer, Long> pidRoidMap = new HashMap<>();
		ifcModel.getModelMetaData().setName(name);
		ifcModel.getModelMetaData().setRevisionId(1);
		if (getAuthorization().getUoid() != -1) {
			ifcModel.getModelMetaData().setAuthorizedUser(getUserByUoid(getAuthorization().getUoid()).getName());
		}
		ifcModel.getModelMetaData().setDate(new Date());
		return ifcModel;

		} catch (IOException e1) {
			e1.printStackTrace();
		} catch (IfcModelInterfaceException e) {
			e.printStackTrace();
		} catch (QueryException e) {
			e.printStackTrace();
		}

//		for (Long roid : roids) {
//			Revision virtualRevision = getRevisionByRoid(roid);
//			pidRoidMap.put(virtualRevision.getProject().getId(), virtualRevision.getOid());
//			project = virtualRevision.getProject();
//			name += project.getName() + "-" + virtualRevision.getId() + "-";
//			try {
//				getAuthorization().canDownload(roid);
//			} catch (UserException e) {
//				if (!getAuthorization().hasRightsOnProjectOrSuperProjectsOrSubProjects(user, project)) {
//					throw new UserException("User has insufficient rights to download revisions from this project");
//				}
//				if (!getAuthorization().hasRightsOnProjectOrSuperProjectsOrSubProjects(user, project)) {
//					throw new UserException("User has insufficient rights to download revisions from this project");
//				}
//			}
//			int size = 0;
//			
//			for (ConcreteRevision concreteRevision : virtualRevision.getConcreteRevisions()) {
//				try {
//					int highestStopId = findHighestStopRid(project, concreteRevision);
//					
//					PackageMetaData packageMetaData = getBimServer().getMetaDataManager().getPackageMetaData(concreteRevision.getProject().getSchema());
//					lastPackageMetaData = packageMetaData;
//					IfcModelInterface subModel = new ServerIfcModel(packageMetaData, pidRoidMap, getDatabaseSession());
//					
//					OldQuery databaseQuery = new OldQuery(packageMetaData, concreteRevision.getProject().getId(), concreteRevision.getId(), virtualRevision.getOid(), null, Deep.NO, highestStopId);
//					databaseQuery.updateOidCounters(concreteRevision, getDatabaseSession());
//					JsonObject queryObject = (JsonObject)query;
//					JsonArray queries = queryObject.get("queries").getAsJsonArray();
//					for (JsonElement queryElement : queries) {
//						processQueryPart(packageMetaData, queryObject, (JsonObject) queryElement, subModel, databaseQuery);
//					}
//					
//					size += subModel.size();
//					subModel.getModelMetaData().setDate(concreteRevision.getDate());
//					subModel.fixInverseMismatches();
//					checkGeometry(serializerPluginConfiguration, getBimServer().getPluginManager(), subModel, project, concreteRevision, virtualRevision);
//					ifcModelSet.add(subModel);
//				} catch (GeometryGeneratingException | IfcModelInterfaceException e) {
//					throw new UserException(e);
//				}
//			}
//
//			IfcModelInterface ifcModel = new ServerIfcModel(lastPackageMetaData, pidRoidMap, size, getDatabaseSession());
//			if (ifcModelSet.size() > 1) {
//				try {
//					ifcModel = getBimServer().getMergerFactory().createMerger(getDatabaseSession(), getAuthorization().getUoid()).merge(project, ifcModelSet, new ModelHelper(getBimServer().getMetaDataManager(), ifcModel));
//				} catch (MergeException e) {
//					throw new UserException(e);
//				}
//			} else {
//				ifcModel = ifcModelSet.iterator().next();
//			}
//			ifcModel.getModelMetaData().setName("Unknown");
//			ifcModel.getModelMetaData().setRevisionId(project.getRevisions().indexOf(virtualRevision) + 1);
//			if (getAuthorization().getUoid() != -1) {
//				ifcModel.getModelMetaData().setAuthorizedUser(getUserByUoid(getAuthorization().getUoid()).getName());
//			}
//			ifcModel.getModelMetaData().setDate(virtualRevision.getDate());
//		}
		// TODO check, double merging??
//		IfcModelInterface ifcModel = new BasicIfcModel(lastPackageMetaData, pidRoidMap);
//		if (ifcModelSet.size() > 1) {
//			try {
//				ifcModel = getBimServer().getMergerFactory().createMerger(getDatabaseSession(), getAuthorization().getUoid()).merge(project, ifcModelSet, new ModelHelper(getBimServer().getMetaDataManager(), ifcModel));
//			} catch (MergeException e) {
//				throw new UserException(e);
//			}
//		} else {
//			ifcModel = ifcModelSet.iterator().next();
//		}
//		if (name.endsWith("-")) {
//			name = name.substring(0, name.length()-1);
//		}
		return null;
	}
	
	public int getProgress() {
		return progress;
	}
}