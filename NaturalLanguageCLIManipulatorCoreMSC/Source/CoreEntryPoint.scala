import amy.ipc.*

// Walking-skeleton entry point: proves the loopback socket, protobuf
// framing, and all three IPC flows (Confirmation, Access Request,
// Clarification) work end to end. No real Agent reasoning, Tools, or
// Safety logic yet - see ARCHITECTURE.md.
@main def CoreEntryPoint(): Unit =
  val channel = UserInterfaceChannel.start()

  channel.send(
    CoreToUserInterface(
      CoreToUserInterface.Payload.ConfirmationRequest(
        ConfirmationRequest(
          command = "git push --force",
          reason = "This rewrites remote history and can discard other people's commits."
        )
      )
    )
  )
  channel.receive().map(_.payload) match
    case Some(UserInterfaceToCore.Payload.ConfirmationResponse(response)) =>
      channel.send(echo(if response.approved then "Confirmed." else "Declined."))
    case other => System.err.println(s"Core: unexpected response: $other")

  channel.send(
    CoreToUserInterface(
      CoreToUserInterface.Payload.AccessRequest(
        AccessRequest(
          directory = "C:\\Users\\example\\Photos",
          reason = "Needed to list and move image files for this Request."
        )
      )
    )
  )
  channel.receive().map(_.payload) match
    case Some(UserInterfaceToCore.Payload.AccessResponse(response)) =>
      channel.send(echo(s"Access decision: ${response.decision}"))
    case other => System.err.println(s"Core: unexpected response: $other")

  channel.send(
    CoreToUserInterface(
      CoreToUserInterface.Payload.ClarificationRequest(
        ClarificationRequest(
          question = "Which folder should the sorted images go into?",
          choices = Seq("Create a new dated folder", "Use an existing folder")
        )
      )
    )
  )
  channel.receive().map(_.payload) match
    case Some(UserInterfaceToCore.Payload.ClarificationResponse(response)) =>
      channel.send(echo(s"Answer: ${response.answerText}"))
    case other => System.err.println(s"Core: unexpected response: $other")

  channel.close()

private def echo(text: String): CoreToUserInterface =
  CoreToUserInterface(CoreToUserInterface.Payload.OutputLine(OutputLine(text)))
