package org.bimserver.demoplugins.service;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.emf.OidProvider;
import org.bimserver.ifc.Scaler;
import org.bimserver.interfaces.objects.SActionState;
import org.bimserver.interfaces.objects.SInternalServicePluginConfiguration;
import org.bimserver.interfaces.objects.SLongActionState;
import org.bimserver.interfaces.objects.SObjectType;
import org.bimserver.interfaces.objects.SProgressTopicType;
import org.bimserver.interfaces.objects.SProject;
import org.bimserver.interfaces.objects.SRevision;
import org.bimserver.interfaces.objects.SService;
import org.bimserver.models.ifc2x3tc1.Ifc2x3tc1Package;
import org.bimserver.models.ifc2x3tc1.IfcAxis2Placement3D;
import org.bimserver.models.ifc2x3tc1.IfcBuildingStorey;
import org.bimserver.models.ifc2x3tc1.IfcCartesianPoint;
import org.bimserver.models.ifc2x3tc1.IfcFurnishingElement;
import org.bimserver.models.ifc2x3tc1.IfcLocalPlacement;
import org.bimserver.models.ifc2x3tc1.IfcObjectDefinition;
import org.bimserver.models.ifc2x3tc1.IfcOwnerHistory;
import org.bimserver.models.ifc2x3tc1.IfcProductDefinitionShape;
import org.bimserver.models.ifc2x3tc1.IfcRelContainedInSpatialStructure;
import org.bimserver.models.ifc2x3tc1.IfcRelDecomposes;
import org.bimserver.models.ifc2x3tc1.IfcRelDefines;
import org.bimserver.models.ifc2x3tc1.IfcRepresentation;
import org.bimserver.models.ifc2x3tc1.IfcSIPrefix;
import org.bimserver.models.ifc2x3tc1.IfcSIUnit;
import org.bimserver.models.ifc2x3tc1.IfcSIUnitName;
import org.bimserver.models.ifc2x3tc1.IfcShapeRepresentation;
import org.bimserver.models.ifc2x3tc1.IfcSpace;
import org.bimserver.models.ifc2x3tc1.IfcUnitEnum;
import org.bimserver.models.log.AccessMethod;
import org.bimserver.models.store.ObjectDefinition;
import org.bimserver.models.store.ServiceDescriptor;
import org.bimserver.models.store.StoreFactory;
import org.bimserver.models.store.Trigger;
import org.bimserver.plugins.ModelHelper;
import org.bimserver.plugins.PluginConfiguration;
import org.bimserver.plugins.PluginException;
import org.bimserver.plugins.PluginManager;
import org.bimserver.plugins.deserializers.Deserializer;
import org.bimserver.plugins.deserializers.DeserializerPlugin;
import org.bimserver.plugins.objectidms.HideAllInversesObjectIDM;
import org.bimserver.plugins.services.BimServerClientInterface;
import org.bimserver.plugins.services.NewRevisionHandler;
import org.bimserver.plugins.services.ServicePlugin;
import org.bimserver.shared.GuidCompressor;
import org.bimserver.shared.exceptions.ServerException;
import org.bimserver.shared.exceptions.UserException;
import org.bimserver.utils.CollectionUtils;
import org.eclipse.emf.ecore.EClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FurniturePlacerServicePlugin extends ServicePlugin {

	private static final Logger LOGGER = LoggerFactory.getLogger(FurniturePlacerServicePlugin.class);
	private boolean initialized;

	@Override
	public void init(PluginManager pluginManager) throws PluginException {
		super.init(pluginManager);
		initialized = true;
	}
	
	@Override
	public String getDescription() {
		return "Furniture Placer";
	}

	@Override
	public String getDefaultName() {
		return "Furniture Placer";
	}

	@Override
	public String getVersion() {
		return "1.0";
	}

	@Override
	public ObjectDefinition getSettingsDefinition() {
		return null;
	}

	@Override
	public boolean isInitialized() {
		return initialized;
	}

	@Override
	public String getTitle() {
		return "Furniture Placer";
	}

	@Override
	public void register(long uoid, SInternalServicePluginConfiguration internalService, PluginConfiguration pluginConfiguration) {
		ServiceDescriptor serviceDescriptor = StoreFactory.eINSTANCE.createServiceDescriptor();
		serviceDescriptor.setProviderName("BIMserver");
		serviceDescriptor.setIdentifier("" + internalService.getOid());
		serviceDescriptor.setName("Furniture Placer");
		serviceDescriptor.setDescription("Furniture Placer");
		serviceDescriptor.setWriteRevision(true);
		serviceDescriptor.setReadRevision(true);
		serviceDescriptor.setNotificationProtocol(AccessMethod.INTERNAL);
		serviceDescriptor.setTrigger(Trigger.NEW_REVISION);
		registerNewRevisionHandler(uoid, serviceDescriptor, new NewRevisionHandler() {
			@Override
			public void newRevision(BimServerClientInterface bimServerClientInterface, long poid, long roid, String userToken, long soid, SObjectType settings) throws ServerException, UserException {
				try {
					SRevision revision = bimServerClientInterface.getBimsie1ServiceInterface().getRevision(roid);
					if (revision.getComment().equals("Added furniture")) {
						LOGGER.info("Skipping new revision because seems to be generated by Furniture Placer");
						return;
					}

					Date startDate = new Date();
					Long topicId = bimServerClientInterface.getRegistry().registerProgressOnRevisionTopic(SProgressTopicType.RUNNING_SERVICE, poid, roid, "Running Furniture Placer");
					SLongActionState state = new SLongActionState();
					state.setTitle("Furniture Placer");
					state.setState(SActionState.STARTED);
					state.setProgress(-1);
					state.setStart(startDate);
					bimServerClientInterface.getRegistry().updateProgressTopic(topicId, state);
					
					SService service = bimServerClientInterface.getServiceInterface().getService(soid);
					
					SProject project = bimServerClientInterface.getBimsie1ServiceInterface().getProjectByPoid(poid);
					final IfcModelInterface model = bimServerClientInterface.getModel(project, roid, true, false);
					
					DeserializerPlugin deserializerPlugin = getPluginManager().getDeserializerPlugin("org.bimserver.ifc.step.deserializer.Ifc2x3tc1StepDeserializerPlugin", true);
					
					Deserializer deserializer = deserializerPlugin.createDeserializer(null);
					deserializer.init(model.getPackageMetaData());
					InputStream resourceAsInputStream = getPluginManager().getPluginContext(FurniturePlacerServicePlugin.this).getResourceAsInputStream("data/picknicktable.ifc");
					ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
					IOUtils.copy(resourceAsInputStream, byteArrayOutputStream);
					resourceAsInputStream.close();
					IfcModelInterface furnishingModel = deserializer.read(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()), "picknicktable.ifc", byteArrayOutputStream.size());

					IfcSIPrefix prefix = IfcSIPrefix.NULL;
					for (IfcSIUnit ifcUnit : model.getAllWithSubTypes(IfcSIUnit.class)) {
						if (ifcUnit.getUnitType() == IfcUnitEnum.LENGTHUNIT && ifcUnit.getName() == IfcSIUnitName.METRE) {
							prefix = ifcUnit.getPrefix();
							break;
						}
					}
					LOGGER.info("Length exponent: " + prefix);
					
					IfcFurnishingElement picknick = (IfcFurnishingElement) furnishingModel.getByName(Ifc2x3tc1Package.eINSTANCE.getIfcFurnishingElement(), "Picknik Bank");
					
					if (prefix != IfcSIPrefix.NULL) {
						if (prefix == IfcSIPrefix.MILLI) {
							// Picknick model is in meter, target model in millis, so we have to convert the picknick model first
							Scaler scaler = new Scaler(furnishingModel);
							scaler.scale(1000f);
						} else {
							LOGGER.info("Unimplemented prefix: " + prefix);
						}
					}

					ModelHelper modelHelper = new ModelHelper(getPluginManager().getMetaDataManager(), new HideAllInversesObjectIDM(CollectionUtils.singleSet(Ifc2x3tc1Package.eINSTANCE), getPluginManager().requireSchemaDefinition("ifc2x3tc1")), model);

					modelHelper.setTargetModel(model);
					modelHelper.setObjectFactory(model);
					OidProvider<Long> oidProvider = new OidProvider<Long>() {
						long c = model.getHighestOid() + 1;
						@Override
						public Long newOid(EClass eClass) {
							return c++;
						}
					};
					modelHelper.setOidProvider(oidProvider);
					
					IfcProductDefinitionShape representation = (IfcProductDefinitionShape) picknick.getRepresentation();
					IfcRepresentation surfaceModel = null;
					IfcRepresentation boundingBox = null;
					for (IfcRepresentation ifcRepresentation : representation.getRepresentations()) {
						IfcShapeRepresentation ifcShapeRepresentation = (IfcShapeRepresentation)ifcRepresentation;
						if (ifcShapeRepresentation.getRepresentationType().equals("SurfaceModel")) {
							surfaceModel = (IfcRepresentation) modelHelper.copy(ifcShapeRepresentation, true);
						} else if (ifcShapeRepresentation.getRepresentationType().equals("BoundingBox")) {
							boundingBox	= (IfcRepresentation) modelHelper.copy(ifcShapeRepresentation, true);
						}
					}
					
					List<IfcRelDefines> newDefines = new ArrayList<>();
					for (IfcRelDefines ifcRelDefines : picknick.getIsDefinedBy()) {
						newDefines.add((IfcRelDefines) modelHelper.copy(ifcRelDefines, true));
					}

					IfcOwnerHistory ownerHistory = null;
					List<IfcOwnerHistory> all = model.getAll(IfcOwnerHistory.class);
					if (all.size() > 0) {
						 ownerHistory = all.get(0);
					}
					int newFurniture = 0;
					for (IfcBuildingStorey ifcBuildingStorey : model.getAll(IfcBuildingStorey.class)) {
						for (IfcRelDecomposes ifcRelDecomposes : ifcBuildingStorey.getIsDecomposedBy()) {
							for (IfcObjectDefinition ifcObjectDefinition : ifcRelDecomposes.getRelatedObjects()) {
								if (ifcObjectDefinition instanceof IfcSpace) {
									IfcSpace ifcSpace = (IfcSpace)ifcObjectDefinition;
									
									IfcFurnishingElement newFurnishing = model.create(IfcFurnishingElement.class, oidProvider);
									newFurnishing.setName("ADDED FURNITURE");
									
									newFurnishing.setGlobalId(GuidCompressor.getNewIfcGloballyUniqueId());
									newFurnishing.setOwnerHistory(ownerHistory);
									IfcProductDefinitionShape definitionShape = model.create(IfcProductDefinitionShape.class, oidProvider);
									newFurnishing.setRepresentation(definitionShape);
									
									definitionShape.getRepresentations().add(boundingBox);
									definitionShape.getRepresentations().add(surfaceModel);
									
									for (IfcRelDefines ifcRelDefines : newDefines) {
										newFurnishing.getIsDefinedBy().add(ifcRelDefines);
									}
									
									IfcLocalPlacement localPlacement = model.create(IfcLocalPlacement.class, oidProvider);
									localPlacement.setPlacementRelTo(ifcSpace.getObjectPlacement());
									IfcAxis2Placement3D axis2Placement3D = model.create(IfcAxis2Placement3D.class, oidProvider);
									localPlacement.setRelativePlacement(axis2Placement3D);
									
									IfcCartesianPoint pos = model.create(IfcCartesianPoint.class, oidProvider);
									pos.getCoordinates().add(0d);
									pos.getCoordinates().add(0d);
									pos.getCoordinates().add(0d);
									axis2Placement3D.setLocation(pos);
									
									if (ifcSpace.getContainsElements().size() > 0) {
										IfcRelContainedInSpatialStructure rel = ifcSpace.getContainsElements().get(0);
										rel.getRelatedElements().add(newFurnishing);
									} else {
										IfcRelContainedInSpatialStructure decomposes = model.create(IfcRelContainedInSpatialStructure.class, oidProvider);
										decomposes.setGlobalId(GuidCompressor.getNewIfcGloballyUniqueId());
										decomposes.setOwnerHistory(ownerHistory);
										decomposes.getRelatedElements().add(newFurnishing);
										decomposes.setRelatingStructure(ifcSpace);
									}
									
									newFurnishing.setObjectPlacement(localPlacement);
									
									newFurniture++;
								}
							}
						}
					}
					LOGGER.info("New furniture: " + newFurniture);

					if (service.getWriteRevisionId() != -1 && service.getWriteRevisionId() != project.getOid()) {
						model.checkin(service.getWriteRevisionId(), "Added furniture");
					} else {
						model.commit("Added furniture");
					}
					
					state = new SLongActionState();
					state.setProgress(100);
					state.setTitle("Furniture Placer");
					state.setState(SActionState.FINISHED);
					state.setStart(startDate);
					state.setEnd(new Date());
					bimServerClientInterface.getRegistry().updateProgressTopic(topicId, state);
					
					bimServerClientInterface.getRegistry().unregisterProgressTopic(topicId);
				} catch (Exception e) {
					LOGGER.error("", e);
				}
			}
		});
	}

	@Override
	public void unregister(SInternalServicePluginConfiguration internalService) {
	}
}