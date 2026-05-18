package durable


opaque type WorkflowId = String

object WorkflowId:
  def apply(s: String): WorkflowId = s

  extension (wfId: WorkflowId)
    def value: String = wfId
