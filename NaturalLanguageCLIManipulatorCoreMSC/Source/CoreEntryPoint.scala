import amy.ipc.{ConfirmationRequest, CoreToUserInterface, OutputLine, UserInterfaceChannel, UserInterfaceToCore}

// Walking-skeleton entry point: proves the loopback socket, protobuf
// framing, and the Confirmation flow all work end to end. No real Agent
// reasoning, Tools, or Safety logic yet - see ARCHITECTURE.md.
@main def CoreEntryPoint(): Unit =
  val channel = UserInterfaceChannel.start()

  val confirmationRequest = ConfirmationRequest(
    command = "git push --force",
    reason = "This rewrites remote history and can discard other people's commits."
  )
  channel.send(CoreToUserInterface(CoreToUserInterface.Payload.ConfirmationRequest(confirmationRequest)))

  channel.receive().map(_.payload) match
    case Some(UserInterfaceToCore.Payload.ConfirmationResponse(response)) =>
      val resultText =
        if response.approved then
          "Confirmed. (Walking skeleton stops here - no command is actually run yet.)"
        else "Declined. Stopping."
      channel.send(CoreToUserInterface(CoreToUserInterface.Payload.OutputLine(OutputLine(resultText))))
    case other =>
      System.err.println(s"Core: unexpected response from the User Interface process: $other")

  channel.close()
