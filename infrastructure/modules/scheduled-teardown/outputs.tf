output "codebuild_project_name" {
  description = "Name of the CodeBuild project that runs the nightly destroy. Use with `aws codebuild start-build` to trigger it manually."
  value       = aws_codebuild_project.destroy.name
}

output "schedule_arn" {
  description = "ARN of the EventBridge schedule. Disable it (or set state = DISABLED) to pause automatic teardown without removing the resources."
  value       = aws_scheduler_schedule.nightly_destroy.arn
}
