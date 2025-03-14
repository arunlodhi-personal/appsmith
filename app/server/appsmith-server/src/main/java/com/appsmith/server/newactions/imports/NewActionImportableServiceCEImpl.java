package com.appsmith.server.newactions.imports;

import com.appsmith.external.models.ActionDTO;
import com.appsmith.external.models.DefaultResources;
import com.appsmith.external.models.Policy;
import com.appsmith.server.actioncollections.base.ActionCollectionService;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.defaultresources.DefaultResourcesService;
import com.appsmith.server.domains.ActionCollection;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.dtos.ImportActionCollectionResultDTO;
import com.appsmith.server.dtos.ImportActionResultDTO;
import com.appsmith.server.dtos.ImportingMetaDTO;
import com.appsmith.server.dtos.MappedImportableResourcesDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ce.ImportArtifactPermissionProvider;
import com.appsmith.server.imports.importable.ImportableServiceCE;
import com.appsmith.server.newactions.base.NewActionService;
import com.appsmith.server.repositories.NewActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.appsmith.external.helpers.AppsmithBeanUtils.copyNestedNonNullProperties;
import static com.appsmith.server.helpers.ImportExportUtils.sanitizeDatasourceInActionDTO;
import static java.lang.Boolean.TRUE;

@Slf4j
@RequiredArgsConstructor
public class NewActionImportableServiceCEImpl implements ImportableServiceCE<NewAction> {

    private final NewActionService newActionService;
    private final NewActionRepository repository;
    private final ActionCollectionService actionCollectionService;
    private final DefaultResourcesService<NewAction> defaultResourcesService;
    private final DefaultResourcesService<ActionDTO> dtoDefaultResourcesService;

    // Requires pageNameMap, pageNameToOldNameMap, pluginMap and datasourceNameToIdMap, to be present in importable
    // resources.
    // Updates actionResultDTO in importable resources.
    // Also, directly updates required information in DB
    @Override
    public Mono<Void> importEntities(
            ImportingMetaDTO importingMetaDTO,
            MappedImportableResourcesDTO mappedImportableResourcesDTO,
            Mono<Workspace> workspaceMono,
            Mono<Application> applicationMono,
            ApplicationJson applicationJson) {

        List<NewAction> importedNewActionList = applicationJson.getActionList();

        Mono<List<NewAction>> importedNewActionMono = Mono.justOrEmpty(importedNewActionList);
        if (TRUE.equals(importingMetaDTO.getAppendToArtifact())) {
            importedNewActionMono = importedNewActionMono.map(importedNewActionList1 -> {
                List<NewPage> importedNewPages = mappedImportableResourcesDTO.getPageOrModuleMap().values().stream()
                        .distinct()
                        .map(branchAwareDomain -> (NewPage) branchAwareDomain)
                        .toList();
                Map<String, String> newToOldNameMap = mappedImportableResourcesDTO.getPageOrModuleNewNameToOldName();

                for (NewPage newPage : importedNewPages) {
                    String newPageName = newPage.getUnpublishedPage().getName();
                    String oldPageName = newToOldNameMap.get(newPageName);

                    if (!newPageName.equals(oldPageName)) {
                        renamePageInActions(importedNewActionList1, oldPageName, newPageName);
                    }
                }
                return importedNewActionList1;
            });
        }

        return Mono.zip(importedNewActionMono, applicationMono)
                .flatMap(objects ->
                        importActions(objects.getT1(), objects.getT2(), importingMetaDTO, mappedImportableResourcesDTO))
                .flatMap(importActionResultDTO -> {
                    log.info("Actions imported. result: {}", importActionResultDTO.getGist());
                    // Updating the existing application for git-sync
                    // During partial import/appending to the existing application keep the resources
                    // attached to the application:
                    // Delete the invalid resources (which are not the part of applicationJsonDTO) in
                    // the git flow only
                    if (StringUtils.hasText(importingMetaDTO.getArtifactId())
                            && !TRUE.equals(importingMetaDTO.getAppendToArtifact())
                            && CollectionUtils.isNotEmpty(importActionResultDTO.getExistingActions())) {
                        // Remove unwanted actions
                        Set<String> invalidActionIds = new HashSet<>();
                        if (Boolean.FALSE.equals(importingMetaDTO.getIsPartialImport())) {
                            for (NewAction action : importActionResultDTO.getExistingActions()) {
                                if (!importActionResultDTO
                                        .getImportedActionIds()
                                        .contains(action.getId())) {
                                    invalidActionIds.add(action.getId());
                                }
                            }
                        }
                        log.info("Deleting {} actions which are no more used", invalidActionIds.size());
                        return Flux.fromIterable(invalidActionIds)
                                .flatMap(actionId -> newActionService
                                        .deleteUnpublishedAction(actionId)
                                        // return an empty action so that the filter can remove it from the list
                                        .onErrorResume(throwable -> {
                                            log.debug("Failed to delete action with id {} during import", actionId);
                                            log.error(throwable.getMessage());
                                            return Mono.empty();
                                        }))
                                .then()
                                .thenReturn(importActionResultDTO);
                    }
                    return Mono.just(importActionResultDTO);
                })
                .onErrorResume(throwable -> {
                    log.error("Error while importing actions and deleting unused ones", throwable);
                    return Mono.error(throwable);
                })
                .then();
    }

    @Override
    public Mono<Void> updateImportedEntities(
            Application application,
            ImportingMetaDTO importingMetaDTO,
            MappedImportableResourcesDTO mappedImportableResourcesDTO) {

        ImportActionResultDTO importActionResultDTO = mappedImportableResourcesDTO.getActionResultDTO();
        ImportActionCollectionResultDTO importActionCollectionResultDTO =
                mappedImportableResourcesDTO.getActionCollectionResultDTO();

        List<String> savedCollectionIds = importActionCollectionResultDTO.getSavedActionCollectionIds();
        log.info(
                "Action collections imported. applicationId {}, result: {}",
                application.getId(),
                importActionCollectionResultDTO.getGist());
        return newActionService
                .updateActionsWithImportedCollectionIds(importActionCollectionResultDTO, importActionResultDTO)
                .flatMap(actionAndCollectionMapsDTO -> {
                    log.info("Updated actions with imported collection ids. applicationId {}", application.getId());
                    // Updating the existing application for git-sync
                    // During partial import/appending to the existing application keep the resources
                    // attached to the application:
                    // Delete the invalid resources (which are not the part of applicationJsonDTO) in
                    // the git flow only
                    if (StringUtils.hasText(importingMetaDTO.getArtifactId())
                            && !TRUE.equals(importingMetaDTO.getAppendToArtifact())
                            && Boolean.FALSE.equals(importingMetaDTO.getIsPartialImport())) {
                        // Remove unwanted action collections
                        Set<String> invalidCollectionIds = new HashSet<>();
                        for (ActionCollection collection :
                                importActionCollectionResultDTO.getExistingActionCollections()) {
                            if (!savedCollectionIds.contains(collection.getId())) {
                                invalidCollectionIds.add(collection.getId());
                            }
                        }
                        log.info("Deleting {} action collections which are no more used", invalidCollectionIds.size());
                        return Flux.fromIterable(invalidCollectionIds)
                                .flatMap(collectionId -> actionCollectionService
                                        .deleteWithoutPermissionUnpublishedActionCollection(collectionId)
                                        // return an empty collection so that the filter can remove it from
                                        // the list
                                        .onErrorResume(throwable -> {
                                            log.debug(
                                                    "Failed to delete collection with id {} during import",
                                                    collectionId);
                                            log.error(throwable.getMessage());
                                            return Mono.empty();
                                        }))
                                .then();
                    }

                    mappedImportableResourcesDTO.setActionAndCollectionMapsDTO(actionAndCollectionMapsDTO);

                    return Mono.empty().then();
                })
                .onErrorResume(error -> {
                    log.error("Error while updating collection id to actions", error);
                    return Mono.error(error);
                });
    }

    private void renamePageInActions(List<NewAction> newActionList, String oldPageName, String newPageName) {
        for (NewAction newAction : newActionList) {
            if (newAction.getUnpublishedAction().getPageId().equals(oldPageName)) {
                newAction.getUnpublishedAction().setPageId(newPageName);
            }
        }
    }

    /**
     * Method to
     * - save imported actions with updated policies
     * - update default resource ids along with branch-name if the application is connected to git
     * - update the map of imported collectionIds to the actionIds in saved in DB
     *
     * @param importedNewActionList action list extracted from the imported JSON file
     * @param application           imported and saved application in DB
     * @return A DTO class with several information
     */
    private Mono<ImportActionResultDTO> importActions(
            List<NewAction> importedNewActionList,
            Application application,
            ImportingMetaDTO importingMetaDTO,
            MappedImportableResourcesDTO mappedImportableResourcesDTO) {
        /* Mono.just(application) is created to avoid the eagerly fetching of existing actions
         * during the pipeline construction. It should be fetched only when the pipeline is subscribed/executed.
         */
        return Mono.just(application)
                .flatMap(importedApplication -> {
                    Mono<Map<String, NewAction>> actionsInCurrentAppMono =
                            getActionsInCurrentAppMono(importedApplication).collectMap(NewAction::getGitSyncId);

                    // find existing actions in all the branches of this application and put them in a map
                    Mono<Map<String, NewAction>> actionsInOtherBranchesMono;
                    if (importedApplication.getGitApplicationMetadata() != null) {
                        final String defaultApplicationId =
                                importedApplication.getGitApplicationMetadata().getDefaultApplicationId();
                        actionsInOtherBranchesMono = getActionInOtherBranchesMono(defaultApplicationId)
                                .filter(newAction -> newAction.getGitSyncId() != null)
                                .collectMap(NewAction::getGitSyncId);
                    } else {
                        actionsInOtherBranchesMono = Mono.just(Collections.emptyMap());
                    }

                    // update the action name in the json to avoid duplicate names for the partial import
                    // It is page level action and hence the action name should be unique
                    if (Boolean.TRUE.equals(importingMetaDTO.getIsPartialImport())
                            && mappedImportableResourcesDTO.getRefactoringNameReference() != null) {
                        updateActionNameBeforeMerge(
                                importedNewActionList, mappedImportableResourcesDTO.getRefactoringNameReference());
                    }

                    return Mono.zip(actionsInCurrentAppMono, actionsInOtherBranchesMono)
                            .flatMap(objects -> {
                                Map<String, NewAction> actionsInCurrentApp = objects.getT1();
                                Map<String, NewAction> actionsInOtherBranches = objects.getT2();

                                List<NewAction> newNewActionList = new ArrayList<>();
                                List<NewAction> existingNewActionList = new ArrayList<>();

                                final String workspaceId = importedApplication.getWorkspaceId();

                                ImportActionResultDTO importActionResultDTO = new ImportActionResultDTO();

                                // existing actions will be required when we'll delete the outdated actions later
                                importActionResultDTO.setExistingActions(actionsInCurrentApp.values());

                                for (NewAction newAction : importedNewActionList) {
                                    if (newAction.getUnpublishedAction() == null
                                            || !StringUtils.hasLength(newAction
                                                    .getUnpublishedAction()
                                                    .getPageId())) {
                                        continue;
                                    }

                                    NewPage parentPage = new NewPage();
                                    ActionDTO unpublishedAction = newAction.getUnpublishedAction();
                                    ActionDTO publishedAction = newAction.getPublishedAction();

                                    // If pageId is missing in the actionDTO create a fallback pageId
                                    final String fallbackParentPageId = unpublishedAction.getPageId();

                                    if (unpublishedAction.getValidName() != null) {
                                        unpublishedAction.setId(newAction.getId());
                                        parentPage = updatePageInAction(
                                                unpublishedAction,
                                                (Map<String, NewPage>)
                                                        mappedImportableResourcesDTO.getPageOrModuleMap(),
                                                importActionResultDTO.getActionIdMap());
                                        sanitizeDatasourceInActionDTO(
                                                unpublishedAction,
                                                mappedImportableResourcesDTO.getDatasourceNameToIdMap(),
                                                mappedImportableResourcesDTO.getPluginMap(),
                                                workspaceId,
                                                false);
                                    }

                                    if (publishedAction != null && publishedAction.getValidName() != null) {
                                        publishedAction.setId(newAction.getId());
                                        if (!StringUtils.hasLength(publishedAction.getPageId())) {
                                            publishedAction.setPageId(fallbackParentPageId);
                                        }
                                        NewPage publishedActionPage = updatePageInAction(
                                                publishedAction,
                                                (Map<String, NewPage>)
                                                        mappedImportableResourcesDTO.getPageOrModuleMap(),
                                                importActionResultDTO.getActionIdMap());
                                        parentPage = parentPage == null ? publishedActionPage : parentPage;
                                        sanitizeDatasourceInActionDTO(
                                                publishedAction,
                                                mappedImportableResourcesDTO.getDatasourceNameToIdMap(),
                                                mappedImportableResourcesDTO.getPluginMap(),
                                                workspaceId,
                                                false);
                                    }

                                    newAction.makePristine();
                                    newAction.setWorkspaceId(workspaceId);
                                    newAction.setApplicationId(importedApplication.getId());
                                    newAction.setPluginId(mappedImportableResourcesDTO
                                            .getPluginMap()
                                            .get(newAction.getPluginId()));
                                    newActionService.generateAndSetActionPolicies(parentPage, newAction);

                                    if (importedApplication.getGitApplicationMetadata() != null) {
                                        // application is git connected, check if the action is already present in
                                        // any other branch
                                        if (actionsInOtherBranches.containsKey(newAction.getGitSyncId())) {
                                            // action found in other branch, copy the default resources from that
                                            // action
                                            NewAction branchedAction = getExistingActionInOtherBranchForImportedAction(
                                                    mappedImportableResourcesDTO, actionsInOtherBranches, newAction);
                                            defaultResourcesService.setFromOtherBranch(
                                                    newAction, branchedAction, importingMetaDTO.getBranchName());
                                            dtoDefaultResourcesService.setFromOtherBranch(
                                                    newAction.getUnpublishedAction(),
                                                    branchedAction.getUnpublishedAction(),
                                                    importingMetaDTO.getBranchName());
                                        } else {
                                            // This is the first action we are saving with given gitSyncId in this
                                            // instance
                                            DefaultResources defaultResources = new DefaultResources();
                                            defaultResources.setApplicationId(importedApplication
                                                    .getGitApplicationMetadata()
                                                    .getDefaultApplicationId());
                                            defaultResources.setActionId(newAction.getId());
                                            defaultResources.setBranchName(importingMetaDTO.getBranchName());
                                            newAction.setDefaultResources(defaultResources);
                                        }
                                    } else {
                                        DefaultResources defaultResources = new DefaultResources();
                                        defaultResources.setApplicationId(importedApplication.getId());
                                        defaultResources.setActionId(newAction.getId());
                                        newAction.setDefaultResources(defaultResources);
                                    }

                                    // Check if the action has gitSyncId and if it's already in DB
                                    if (existingAppContainsAction(actionsInCurrentApp, newAction)) {

                                        // Since the resource is already present in DB, just update resource
                                        NewAction existingAction = getExistingActionInCurrentBranchForImportedAction(
                                                mappedImportableResourcesDTO, actionsInCurrentApp, newAction);
                                        updateExistingAction(
                                                existingAction,
                                                newAction,
                                                importingMetaDTO.getBranchName(),
                                                importingMetaDTO.getPermissionProvider());

                                        // Add it to actions list that'll be updated in bulk
                                        existingNewActionList.add(existingAction);
                                        importActionResultDTO
                                                .getImportedActionIds()
                                                .add(existingAction.getId());
                                        putActionIdInMap(existingAction, importActionResultDTO);
                                    } else {
                                        // check whether user has permission to add new action
                                        if (!importingMetaDTO
                                                .getPermissionProvider()
                                                .canCreateAction(parentPage)) {
                                            log.error(
                                                    "User does not have permission to create action in page with id: {}",
                                                    parentPage.getId());
                                            return Mono.error(new AppsmithException(
                                                    AppsmithError.ACL_NO_RESOURCE_FOUND,
                                                    FieldName.PAGE,
                                                    parentPage.getId()));
                                        }

                                        // Add all the properties that will be needed for a new object
                                        populateNewAction(
                                                importingMetaDTO,
                                                mappedImportableResourcesDTO,
                                                importedApplication,
                                                actionsInOtherBranches,
                                                newAction);

                                        // Add it to actions list that'll be inserted or updated in bulk
                                        newNewActionList.add(newAction);
                                        importActionResultDTO
                                                .getImportedActionIds()
                                                .add(newAction.getId());
                                        putActionIdInMap(newAction, importActionResultDTO);
                                    }
                                }

                                log.info(
                                        "Saving actions in bulk. New: {}, Updated: {}",
                                        newNewActionList.size(),
                                        existingNewActionList.size());

                                mappedImportableResourcesDTO.setActionResultDTO(importActionResultDTO);

                                // Save all the new actions in bulk
                                return newActionService
                                        .bulkValidateAndInsertActionInRepository(newNewActionList)
                                        .zipWith(newActionService.bulkValidateAndUpdateActionInRepository(
                                                existingNewActionList))
                                        .thenReturn(importActionResultDTO);
                            });
                })
                .onErrorResume(e -> {
                    log.error("Error importing actions", e);
                    return Mono.error(e);
                })
                .elapsed()
                .map(tuple -> {
                    log.debug(
                            "time to import {} actions: {} ms",
                            tuple.getT2().getImportedActionIds().size(),
                            tuple.getT1());
                    return tuple.getT2();
                });
    }

    private void updateActionNameBeforeMerge(List<NewAction> importedNewActionList, Set<String> refactoringNames) {

        for (NewAction newAction : importedNewActionList) {
            String oldNameAction = newAction.getUnpublishedAction().getName(),
                    newNameAction = newAction.getUnpublishedAction().getName();
            int i = 1;
            while (refactoringNames.contains(newNameAction)) {
                newNameAction = oldNameAction + i++;
            }
            String oldId = newAction.getId().split("_")[1];
            newAction.setId(newNameAction + "_" + oldId);
            newAction.getUnpublishedAction().setName(newNameAction);
            newAction.getUnpublishedAction().setFullyQualifiedName(newNameAction);
            if (newAction.getPublishedAction() != null) {
                newAction.getPublishedAction().setName(newNameAction);
                newAction.getPublishedAction().setFullyQualifiedName(newNameAction);
            }
        }
    }

    private void populateNewAction(
            ImportingMetaDTO importingMetaDTO,
            MappedImportableResourcesDTO mappedImportableResourcesDTO,
            Application importedApplication,
            Map<String, NewAction> actionsInOtherBranches,
            NewAction newAction) {
        // this will generate the id and other auto generated fields e.g. createdAt
        newAction.updateForBulkWriteOperation();

        populateDomainMappedReferences(mappedImportableResourcesDTO, newAction);

        // set gitSyncId, if it doesn't exist
        if (newAction.getGitSyncId() == null) {
            newAction.setGitSyncId(
                    newAction.getApplicationId() + "_" + Instant.now().toString());
        }
    }

    protected NewAction getExistingActionInOtherBranchForImportedAction(
            MappedImportableResourcesDTO mappedImportableResourcesDTO,
            Map<String, NewAction> actionsInOtherBranches,
            NewAction newAction) {
        return actionsInOtherBranches.get(newAction.getGitSyncId());
    }

    protected NewAction getExistingActionInCurrentBranchForImportedAction(
            MappedImportableResourcesDTO mappedImportableResourcesDTO,
            Map<String, NewAction> actionsInCurrentApp,
            NewAction newAction) {
        return actionsInCurrentApp.get(newAction.getGitSyncId());
    }

    protected boolean existingAppContainsAction(Map<String, NewAction> actionsInCurrentApp, NewAction newAction) {
        return newAction.getGitSyncId() != null && actionsInCurrentApp.containsKey(newAction.getGitSyncId());
    }

    protected void populateDomainMappedReferences(
            MappedImportableResourcesDTO mappedImportableResourcesDTO, NewAction newAction) {
        // Nothing needs to be copied into the action from mapped resources
    }

    protected Flux<NewAction> getActionsInCurrentAppMono(Application application) {
        return repository
                .findByApplicationId(application.getId())
                .filter(newAction -> newAction.getGitSyncId() != null);
    }

    protected Flux<NewAction> getActionInOtherBranchesMono(String defaultApplicationId) {
        return repository.findByDefaultApplicationId(defaultApplicationId, Optional.empty());
    }

    private NewPage updatePageInAction(
            ActionDTO action, Map<String, NewPage> pageNameMap, Map<String, String> actionIdMap) {
        NewPage parentPage = pageNameMap.get(action.getPageId());
        if (parentPage == null) {
            return null;
        }
        actionIdMap.put(action.getValidName() + parentPage.getId(), action.getId());
        action.setPageId(parentPage.getId());

        // Update defaultResources in actionDTO
        DefaultResources defaultResources = new DefaultResources();
        defaultResources.setPageId(parentPage.getDefaultResources().getPageId());
        action.setDefaultResources(defaultResources);

        return parentPage;
    }

    private void updateExistingAction(
            NewAction existingAction,
            NewAction actionToImport,
            String branchName,
            ImportArtifactPermissionProvider permissionProvider) {
        // Since the resource is already present in DB, just update resource
        if (!permissionProvider.hasEditPermission(existingAction)) {
            log.error("User does not have permission to edit action with id: {}", existingAction.getId());
            throw new AppsmithException(AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.ACTION, existingAction.getId());
        }
        Set<Policy> existingPolicy = existingAction.getPolicies();

        updateImportableActionFromExistingAction(existingAction, actionToImport);

        copyNestedNonNullProperties(actionToImport, existingAction);
        // Update branchName
        existingAction.getDefaultResources().setBranchName(branchName);
        // Recover the deleted state present in DB from imported action
        existingAction
                .getUnpublishedAction()
                .setDeletedAt(actionToImport.getUnpublishedAction().getDeletedAt());
        existingAction.setDeletedAt(actionToImport.getDeletedAt());
        existingAction.setPolicies(existingPolicy);
    }

    protected void updateImportableActionFromExistingAction(NewAction existingAction, NewAction actionToImport) {
        // Nothing to update from the existing action
    }

    private void putActionIdInMap(NewAction newAction, ImportActionResultDTO importActionResultDTO) {
        // Populate actionIdsMap to associate the appropriate actions to run on page load
        String defaultResourcesActionId = newAction.getDefaultResources().getActionId();

        if (defaultResourcesActionId == null) {
            defaultResourcesActionId = newAction.getId();
            newAction.getDefaultResources().setActionId(newAction.getId());
        }

        if (newAction.getUnpublishedAction() != null) {
            ActionDTO unpublishedAction = newAction.getUnpublishedAction();
            importActionResultDTO
                    .getActionIdMap()
                    .put(
                            importActionResultDTO
                                    .getActionIdMap()
                                    .get(unpublishedAction.getValidName() + unpublishedAction.getPageId()),
                            newAction.getId());

            if (unpublishedAction.getCollectionId() != null) {
                importActionResultDTO
                        .getUnpublishedCollectionIdToActionIdsMap()
                        .putIfAbsent(unpublishedAction.getCollectionId(), new HashMap<>());
                final Map<String, String> actionIds = importActionResultDTO
                        .getUnpublishedCollectionIdToActionIdsMap()
                        .get(unpublishedAction.getCollectionId());

                actionIds.put(defaultResourcesActionId, newAction.getId());
            }
        }
        if (newAction.getPublishedAction() != null) {
            ActionDTO publishedAction = newAction.getPublishedAction();
            importActionResultDTO
                    .getActionIdMap()
                    .put(
                            importActionResultDTO
                                    .getActionIdMap()
                                    .get(publishedAction.getValidName() + publishedAction.getPageId()),
                            newAction.getId());

            if (publishedAction.getCollectionId() != null) {
                importActionResultDTO
                        .getPublishedCollectionIdToActionIdsMap()
                        .putIfAbsent(publishedAction.getCollectionId(), new HashMap<>());
                final Map<String, String> actionIds = importActionResultDTO
                        .getPublishedCollectionIdToActionIdsMap()
                        .get(publishedAction.getCollectionId());
                actionIds.put(defaultResourcesActionId, newAction.getId());
            }
        }
    }
}
