import { CircleCheck, CircleClose, WarningFilled, Loading } from '@element-plus/icons-vue'
import { STATUS } from './labels'

/**
 * Icon paired with each STATUS enum value.
 *
 * Lives here instead of labels.js so the label module stays icon-free, and here
 * instead of the views because HistoryView and ExecutionResultPanel had
 * verbatim copies that sent CANCELLED/RUNNING to the "success" default (a
 * cancelled run showed a green checkmark).
 */
const STATUS_ICON = {
  [STATUS.SUCCESS]: CircleCheck,
  [STATUS.FAILED]: CircleClose,
  [STATUS.TIMEOUT]: WarningFilled,
  [STATUS.RUNNING]: Loading,
  [STATUS.CANCELLED]: CircleClose
}

export function statusIcon(status) {
  return STATUS_ICON[status] || CircleCheck
}
