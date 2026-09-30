@extends('admin.layout')
@section('title','Activity log')
@section('content')<div class="panel table-wrap"><table><tr><th>Time · UTC</th><th>Staff</th><th>Action</th><th>Subject</th></tr>@foreach($logs as $log)<tr><td>{{ $log->created_at }}</td><td>{{ $log->email ?? 'System' }}</td><td>{{ $log->action }}</td><td>{{ $log->subject }}</td></tr>@endforeach</table><div class="pagination">{{ $logs->links() }}</div></div>@endsection
