import type { ActionResponse, PaginationField } from "api/ActionAPI";
import type {
  EvaluationReduxAction,
  AnyReduxAction,
  ReduxAction,
  ReduxActionWithoutPayload,
} from "@appsmith/constants/ReduxActionConstants";
import type { JSUpdate } from "utils/JSPaneUtils";
import {
  ReduxActionErrorTypes,
  ReduxActionTypes,
} from "@appsmith/constants/ReduxActionConstants";
import type { Action, ActionViewMode } from "entities/Action";
import { batchAction } from "actions/batchActions";
import type { ExecuteErrorPayload } from "constants/AppsmithActionConstants/ActionConstants";
import type { ModalInfo } from "reducers/uiReducers/modalActionReducer";
import type { OtlpSpan } from "UITelemetry/generateTraces";
import type { ApiResponse } from "api/ApiResponses";
import type { JSCollection } from "entities/JSCollection";

export const createActionRequest = (payload: Partial<Action>) => {
  return {
    type: ReduxActionTypes.CREATE_ACTION_REQUEST,
    payload,
  };
};
export const createActionInit = (payload: Partial<Action>) => {
  return {
    type: ReduxActionTypes.CREATE_ACTION_INIT,
    payload,
  };
};

export const createActionSuccess = (payload: Action) => {
  return {
    type: ReduxActionTypes.CREATE_ACTION_SUCCESS,
    payload,
  };
};

export interface FetchActionsPayload {
  applicationId: string;
  publishedActions?: ApiResponse<ActionViewMode[]>;
  publishedActionCollections?: ApiResponse<JSCollection[]>;
  unpublishedActionCollections?: ApiResponse<JSCollection[]>;
  unpublishedActions?: ApiResponse<Action[]>;
}

export const fetchActions = (
  {
    applicationId,
    unpublishedActions,
  }: { applicationId: string; unpublishedActions?: ApiResponse<Action[]> },
  postEvalActions: Array<AnyReduxAction>,
): EvaluationReduxAction<unknown> => {
  return {
    type: ReduxActionTypes.FETCH_ACTIONS_INIT,
    payload: { applicationId, unpublishedActions },
    postEvalActions,
  };
};

export const fetchActionsForView = ({
  applicationId,
  publishedActions,
}: {
  applicationId: string;
  publishedActions?: ApiResponse<ActionViewMode[]>;
}): ReduxAction<FetchActionsPayload> => {
  return {
    type: ReduxActionTypes.FETCH_ACTIONS_VIEW_MODE_INIT,
    payload: { applicationId, publishedActions },
  };
};

export const fetchActionsForPage = (
  pageId: string,
): EvaluationReduxAction<unknown> => {
  return {
    type: ReduxActionTypes.FETCH_ACTIONS_FOR_PAGE_INIT,
    payload: { pageId },
  };
};

export const fetchActionsForPageSuccess = (
  actions: Action[],
): EvaluationReduxAction<unknown> => {
  return {
    type: ReduxActionTypes.FETCH_ACTIONS_FOR_PAGE_SUCCESS,
    payload: actions,
  };
};

export const fetchActionsForPageError = () => {
  return {
    type: ReduxActionErrorTypes.FETCH_ACTIONS_FOR_PAGE_ERROR,
  };
};

export const runActionViaShortcut = () => {
  return {
    type: ReduxActionTypes.RUN_ACTION_SHORTCUT_REQUEST,
  };
};

export const runAction = (
  id: string,
  paginationField?: PaginationField,
  skipOpeningDebugger = false,
) => {
  return {
    type: ReduxActionTypes.RUN_ACTION_REQUEST,
    payload: {
      id,
      paginationField,
      skipOpeningDebugger,
    },
  };
};

export const softRefreshActions = () => {
  return {
    type: ReduxActionTypes.PLUGIN_SOFT_REFRESH,
  };
};

export const showActionConfirmationModal = (payload: ModalInfo) => {
  return {
    type: ReduxActionTypes.SHOW_ACTION_MODAL,
    payload,
  };
};

export const cancelActionConfirmationModal = (payload: string) => {
  return {
    type: ReduxActionTypes.CANCEL_ACTION_MODAL + `_FOR_${payload.trim()}`,
  };
};

export const acceptActionConfirmationModal = (payload: string) => {
  return {
    type: ReduxActionTypes.CONFIRM_ACTION_MODAL + `_FOR_${payload.trim()}`,
  };
};

export const updateAction = (payload: { id: string }) => {
  return batchAction({
    type: ReduxActionTypes.UPDATE_ACTION_INIT,
    payload,
  });
};

export const updateActionSuccess = (payload: { data: Action }) => {
  return {
    type: ReduxActionTypes.UPDATE_ACTION_SUCCESS,
    payload,
  };
};

export const clearActionResponse = (actionId: string) => {
  return {
    type: ReduxActionTypes.CLEAR_ACTION_RESPONSE,
    payload: {
      actionId,
    },
  };
};

export const deleteAction = (payload: {
  id: string;
  name: string;
  onSuccess?: () => void;
}) => {
  return {
    type: ReduxActionTypes.DELETE_ACTION_INIT,
    payload,
  };
};

export const deleteActionSuccess = (payload: { id: string }) => {
  return {
    type: ReduxActionTypes.DELETE_ACTION_SUCCESS,
    payload,
  };
};

export const moveActionRequest = (payload: {
  id: string;
  destinationPageId: string;
  originalPageId: string;
  name: string;
}) => {
  return {
    type: ReduxActionTypes.MOVE_ACTION_INIT,
    payload,
  };
};

export const moveActionSuccess = (payload: Action) => {
  return {
    type: ReduxActionTypes.MOVE_ACTION_SUCCESS,
    payload,
  };
};

export const moveActionError = (payload: {
  id: string;
  originalPageId: string;
}) => {
  return {
    type: ReduxActionErrorTypes.MOVE_ACTION_ERROR,
    payload,
  };
};

export const copyActionRequest = (payload: {
  id: string;
  destinationPageId: string;
  name: string;
}) => {
  return {
    type: ReduxActionTypes.COPY_ACTION_INIT,
    payload,
  };
};

export const copyActionSuccess = (payload: Action) => {
  return {
    type: ReduxActionTypes.COPY_ACTION_SUCCESS,
    payload,
  };
};

export const copyActionError = (payload: {
  id: string;
  destinationPageId: string;
}) => {
  return {
    type: ReduxActionErrorTypes.COPY_ACTION_ERROR,
    payload,
  };
};

export const executePluginActionRequest = (payload: { id: string }) => ({
  type: ReduxActionTypes.EXECUTE_PLUGIN_ACTION_REQUEST,
  payload: payload,
});

export interface ExecutePluginActionSuccessPayload {
  id: string;
  response: ActionResponse;
  isPageLoad?: boolean;
  isActionCreatedInApp: boolean;
}

export const executePluginActionSuccess = (
  payload: ExecutePluginActionSuccessPayload,
) => ({
  type: ReduxActionTypes.EXECUTE_PLUGIN_ACTION_SUCCESS,
  payload: payload,
});

export const setActionResponseDisplayFormat = (
  payload: UpdateActionPropertyActionPayload,
) => ({
  type: ReduxActionTypes.SET_ACTION_RESPONSE_DISPLAY_FORMAT,
  payload: payload,
});

export const executePluginActionError = (
  executeErrorPayload: ExecuteErrorPayload,
): ReduxAction<ExecuteErrorPayload> => {
  return {
    type: ReduxActionErrorTypes.EXECUTE_PLUGIN_ACTION_ERROR,
    payload: executeErrorPayload,
  };
};

export const saveActionName = (payload: { id: string; name: string }) => ({
  type: ReduxActionTypes.SAVE_ACTION_NAME_INIT,
  payload: payload,
});

export interface SetActionPropertyPayload {
  actionId: string;
  propertyName: string;
  value: any;
  skipSave?: boolean;
}

export const setActionProperty = (
  payload: SetActionPropertyPayload,
  postEvalActions?: Array<AnyReduxAction>,
) => ({
  type: ReduxActionTypes.SET_ACTION_PROPERTY,
  payload,
  postEvalActions,
});

export interface UpdateActionPropertyActionPayload {
  id: string;
  field: string;
  value: any;
}

export const updateActionProperty = (
  payload: UpdateActionPropertyActionPayload,
  postEvalActions?: Array<AnyReduxAction>,
) => {
  return batchAction({
    type: ReduxActionTypes.UPDATE_ACTION_PROPERTY,
    payload,
    postEvalActions,
  });
};

export const executePageLoadActions = (): ReduxActionWithoutPayload => ({
  type: ReduxActionTypes.EXECUTE_PAGE_LOAD_ACTIONS,
});

export const executeJSUpdates = (
  payload: Record<string, JSUpdate>,
): ReduxAction<unknown> => ({
  type: ReduxActionTypes.EXECUTE_JS_UPDATES,
  payload,
});

export const setActionsToExecuteOnPageLoad = (
  actions: Array<{
    executeOnLoad: boolean;
    id: string;
    name: string;
  }>,
) => {
  return {
    type: ReduxActionTypes.SET_ACTION_TO_EXECUTE_ON_PAGELOAD,
    payload: actions,
  };
};

export const setJSActionsToExecuteOnPageLoad = (
  actions: Array<{
    executeOnLoad: boolean;
    id: string;
    name: string;
    collectionId?: string;
  }>,
) => {
  return {
    type: ReduxActionTypes.SET_JS_ACTION_TO_EXECUTE_ON_PAGELOAD,
    payload: actions,
  };
};

export const bindDataOnCanvas = (payload: {
  queryId: string;
  applicationId: string;
  pageId: string;
}) => {
  return {
    type: ReduxActionTypes.BIND_DATA_ON_CANVAS,
    payload,
  };
};

type actionDataPayload = {
  entityName: string;
  dataPath: string;
  data: unknown;
  dataPathRef?: string;
}[];

export interface updateActionDataPayloadType {
  actionDataPayload: actionDataPayload;
  parentSpan?: OtlpSpan;
}
export const updateActionData = (
  payload: actionDataPayload,
  parentSpan?: OtlpSpan,
): {
  type: string;
  payload: updateActionDataPayloadType;
} => {
  return {
    type: ReduxActionTypes.UPDATE_ACTION_DATA,
    payload: {
      actionDataPayload: payload,
      parentSpan,
    },
  };
};

export default {
  createAction: createActionRequest,
  fetchActions,
  runAction: runAction,
  deleteAction,
  deleteActionSuccess,
  updateAction,
  updateActionSuccess,
  bindDataOnCanvas,
};
