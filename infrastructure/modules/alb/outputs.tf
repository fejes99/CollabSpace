output "alb_arn" {
  description = "ALB ARN. Used when attaching WAF ACLs or shield protection in the future."
  value       = aws_lb.main.arn
}

output "alb_dns_name" {
  description = "ALB DNS name (e.g. collabspace-dev-1234567890.eu-central-1.elb.amazonaws.com). This is the public URL of the service during the walking skeleton phase."
  value       = aws_lb.main.dns_name
}

output "alb_zone_id" {
  description = "Hosted zone ID of the ALB. Required when creating Route 53 alias records that point a custom domain at the ALB."
  value       = aws_lb.main.zone_id
}

output "listener_arn" {
  description = "HTTP listener ARN. Each service passes this to aws_lb_listener_rule to attach its own routing rule to the shared listener."
  value       = aws_lb_listener.http.arn
}
